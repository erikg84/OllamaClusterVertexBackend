package com.dallaslabs.services

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

class PerformanceTrackerService(
    private val vertx: Vertx,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val tokenCountPerNode = ConcurrentHashMap<String, AtomicLong>()
    private val tokenCountPerModel = ConcurrentHashMap<String, AtomicLong>()
    private val tokensPerSecondPerNode = ConcurrentHashMap<String, MutableList<Double>>()
    private val tokensPerSecondPerModel = ConcurrentHashMap<String, MutableList<Double>>()
    private val processingTimePerNode = ConcurrentHashMap<String, AtomicLong>()
    private val processingTimePerModel = ConcurrentHashMap<String, AtomicLong>()
    private val maxConcurrentPerNode = ConcurrentHashMap<String, AtomicLong>()
    private val currentConcurrentPerNode = ConcurrentHashMap<String, AtomicLong>()

    private val MAX_SPEED_SAMPLES = 50
    private var lastSnapshotTime = Instant.now().epochSecond
    private val weeklySnapshots = mutableListOf<PerformanceSnapshot>()

    init {
        vertx.setPeriodic(60 * 60 * 1000) {
            try {
                takeSnapshot()
            } catch (e: Exception) {
                launch {
                    logService.logError(
                        "Failed to take performance snapshot",
                        error = e,
                        context = mapOf("interval" to "hourly")
                    )
                }
            }
        }

        vertx.setPeriodic(24 * 60 * 60 * 1000) {
            try {
                cleanupOldSnapshots()
            } catch (e: Exception) {
                launch {
                    logService.logError(
                        "Failed to cleanup performance snapshots",
                        error = e,
                        context = mapOf("interval" to "daily")
                    )
                }
            }
        }
    }

    fun recordRequest(
        nodeName: String,
        modelId: String,
        promptTokens: Int,
        completionTokens: Int,
        processingTimeMs: Long
    ) {
        tokenCountPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
            .addAndGet(completionTokens.toLong())

        tokenCountPerModel.computeIfAbsent(modelId) { AtomicLong(0) }
            .addAndGet(completionTokens.toLong())

        val tokensPerSecond = if (processingTimeMs > 0) {
            (completionTokens.toDouble() / processingTimeMs) * 1000.0
        } else {
            0.0
        }

        synchronized(tokensPerSecondPerNode) {
            val nodeSpeedList = tokensPerSecondPerNode.computeIfAbsent(nodeName) { mutableListOf() }
            nodeSpeedList.add(tokensPerSecond)
            if (nodeSpeedList.size > MAX_SPEED_SAMPLES) {
                nodeSpeedList.removeAt(0)
            }
        }

        synchronized(tokensPerSecondPerModel) {
            val modelSpeedList = tokensPerSecondPerModel.computeIfAbsent(modelId) { mutableListOf() }
            modelSpeedList.add(tokensPerSecond)
            if (modelSpeedList.size > MAX_SPEED_SAMPLES) {
                modelSpeedList.removeAt(0)
            }
        }

        processingTimePerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
            .addAndGet(processingTimeMs)

        processingTimePerModel.computeIfAbsent(modelId) { AtomicLong(0) }
            .addAndGet(processingTimeMs)

        val currentConcurrent = currentConcurrentPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
        currentConcurrent.decrementAndGet()

        logger.debug {
            "Recorded request: node=$nodeName, model=$modelId, " +
                    "tokens=$completionTokens, time=${processingTimeMs}ms, " +
                    "speed=${String.format("%.2f", tokensPerSecond)} tokens/sec"
        }
        launch {
            logService.log("debug", "Request recorded", mapOf(
                "node" to nodeName,
                "model" to modelId,
                "promptTokens" to promptTokens,
                "completionTokens" to completionTokens,
                "processingTimeMs" to processingTimeMs,
                "tokensPerSecond" to tokensPerSecond
            ))
        }
    }

    fun recordRequestStart(nodeName: String) {
        val currentConcurrent = currentConcurrentPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
        val newCount = currentConcurrent.incrementAndGet()

        val maxConcurrent = maxConcurrentPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
        var currentMax = maxConcurrent.get()

        while (newCount > currentMax) {
            if (maxConcurrent.compareAndSet(currentMax, newCount)) {
                break
            }
            currentMax = maxConcurrent.get()
        }

        launch {
            logService.log("debug", "Request started", mapOf(
                "node" to nodeName,
                "currentConcurrentRequests" to newCount,
                "maxConcurrentRequests" to maxConcurrent.get()
            ))
        }
    }

    fun getAverageSpeedForNode(nodeName: String): Double {
        val speeds = tokensPerSecondPerNode[nodeName] ?: return 0.0
        return if (speeds.isEmpty()) 0.0 else speeds.average()
    }

    fun getAverageSpeedForModel(modelId: String): Double {
        val speeds = tokensPerSecondPerModel[modelId] ?: return 0.0
        return if (speeds.isEmpty()) 0.0 else speeds.average()
    }

    fun getCurrentConcurrentRequests(nodeName: String): Long {
        return currentConcurrentPerNode[nodeName]?.get() ?: 0
    }

    fun getMaxConcurrentRequests(nodeName: String): Long {
        return maxConcurrentPerNode[nodeName]?.get() ?: 0
    }

    fun getPerformanceMetrics(): Map<String, Any> {
        val metrics = mutableMapOf<String, Any>()
        val nodeMetrics = mutableMapOf<String, Map<String, Any>>()
        tokenCountPerNode.forEach { (nodeName, tokenCount) ->
            val nodeMap = mutableMapOf<String, Any>()
            nodeMap["totalTokens"] = tokenCount.get()
            nodeMap["averageSpeed"] = getAverageSpeedForNode(nodeName)
            nodeMap["totalProcessingTime"] = processingTimePerNode[nodeName]?.get() ?: 0
            nodeMap["currentConcurrent"] = currentConcurrentPerNode[nodeName]?.get() ?: 0
            nodeMap["maxConcurrent"] = maxConcurrentPerNode[nodeName]?.get() ?: 0
            nodeMetrics[nodeName] = nodeMap
        }
        metrics["nodes"] = nodeMetrics

        val modelMetrics = mutableMapOf<String, Map<String, Any>>()
        tokenCountPerModel.forEach { (modelId, tokenCount) ->
            val modelMap = mutableMapOf<String, Any>()
            modelMap["totalTokens"] = tokenCount.get()
            modelMap["averageSpeed"] = getAverageSpeedForModel(modelId)
            modelMap["totalProcessingTime"] = processingTimePerModel[modelId]?.get() ?: 0
            modelMetrics[modelId] = modelMap
        }
        metrics["models"] = modelMetrics

        metrics["snapshots"] = weeklySnapshots.map { it.toMap() }

        return metrics
    }

    private fun takeSnapshot() {
        val now = Instant.now().epochSecond
        val timeSinceLastSnapshot = now - lastSnapshotTime

        if (timeSinceLastSnapshot < 60 * 60) return

        logger.info { "Taking performance snapshot" }
        launch {
            logService.log("info", "Taking performance snapshot", mapOf(
                "timestamp" to now,
                "timeSinceLastSnapshot" to timeSinceLastSnapshot
            ))
        }

        val nodeSnapshots = mutableMapOf<String, NodeSnapshotData>()
        tokenCountPerNode.forEach { (nodeName, tokenCount) ->
            nodeSnapshots[nodeName] = NodeSnapshotData(
                totalTokens = tokenCount.get(),
                averageSpeed = getAverageSpeedForNode(nodeName),
                maxConcurrent = maxConcurrentPerNode[nodeName]?.get() ?: 0
            )
        }

        val modelSnapshots = mutableMapOf<String, ModelSnapshotData>()
        tokenCountPerModel.forEach { (modelId, tokenCount) ->
            modelSnapshots[modelId] = ModelSnapshotData(
                totalTokens = tokenCount.get(),
                averageSpeed = getAverageSpeedForModel(modelId)
            )
        }

        weeklySnapshots.add(
            PerformanceSnapshot(now, nodeSnapshots, modelSnapshots)
        )
        lastSnapshotTime = now

        logger.info { "Performance snapshot taken at ${Instant.ofEpochSecond(now)}" }
        launch {
            logService.log("info", "Performance snapshot completed", mapOf(
                "timestamp" to now,
                "nodeSnapshotsCount" to nodeSnapshots.size,
                "modelSnapshotsCount" to modelSnapshots.size
            ))
        }
    }

    private fun cleanupOldSnapshots() {
        logger.info { "Cleaning up old performance snapshots" }

        val now = Instant.now().epochSecond
        val oneWeekAgo = now - (7 * 24 * 60 * 60)

        launch {
            logService.log("info", "Cleaning up old performance snapshots", mapOf(
                "currentSnapshotsCount" to weeklySnapshots.size,
                "oneWeekAgoTimestamp" to oneWeekAgo
            ))
        }

        val newList = weeklySnapshots.filter { it.timestamp >= oneWeekAgo }
        weeklySnapshots.clear()
        weeklySnapshots.addAll(newList)

        logger.info { "Kept ${weeklySnapshots.size} snapshots from the last week" }

        launch {
            logService.log("info", "Performance snapshots cleanup completed", mapOf(
                "remainingSnapshotsCount" to weeklySnapshots.size
            ))
        }
    }

    data class NodeSnapshotData(
        val totalTokens: Long,
        val averageSpeed: Double,
        val maxConcurrent: Long
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "totalTokens" to totalTokens,
            "averageSpeed" to averageSpeed,
            "maxConcurrent" to maxConcurrent
        )
    }

    data class ModelSnapshotData(
        val totalTokens: Long,
        val averageSpeed: Double
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "totalTokens" to totalTokens,
            "averageSpeed" to averageSpeed
        )
    }

    data class PerformanceSnapshot(
        val timestamp: Long,
        val nodes: Map<String, NodeSnapshotData>,
        val models: Map<String, ModelSnapshotData>
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "timestamp" to timestamp,
            "nodes" to nodes.mapValues { it.value.toMap() },
            "models" to models.mapValues { it.value.toMap() }
        )
    }
}
