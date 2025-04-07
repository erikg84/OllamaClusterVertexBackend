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

/**
 * Service for tracking performance metrics across nodes and models
 */
class PerformanceTrackerService(
    private val vertx: Vertx,
    private val logService: LogService
): CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    // Track total tokens generated per node
    private val tokenCountPerNode = ConcurrentHashMap<String, AtomicLong>()

    // Track total tokens generated per model
    private val tokenCountPerModel = ConcurrentHashMap<String, AtomicLong>()

    // Track average generation speed (tokens/second) per node
    private val tokensPerSecondPerNode = ConcurrentHashMap<String, MutableList<Double>>()

    // Track average generation speed (tokens/second) per model
    private val tokensPerSecondPerModel = ConcurrentHashMap<String, MutableList<Double>>()

    // Track total processing time per node
    private val processingTimePerNode = ConcurrentHashMap<String, AtomicLong>()

    // Track total processing time per model
    private val processingTimePerModel = ConcurrentHashMap<String, AtomicLong>()

    // Track max concurrent requests per node
    private val maxConcurrentPerNode = ConcurrentHashMap<String, AtomicLong>()

    // Track current concurrent requests per node
    private val currentConcurrentPerNode = ConcurrentHashMap<String, AtomicLong>()

    // Maximum items to keep in speed tracking lists
    private val MAX_SPEED_SAMPLES = 50

    // Last snapshot time
    private var lastSnapshotTime = Instant.now().epochSecond

    // Weekly snapshots for historical data
    private val weeklySnapshots = mutableListOf<PerformanceSnapshot>()

    init {
        // Take a snapshot every hour
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

        // Clean up old snapshots once a day
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

    /**
     * Records metrics for a completed request
     *
     * @param nodeName Name of the node that processed the request
     * @param modelId ID of the model used
     * @param promptTokens Number of tokens in the prompt
     * @param completionTokens Number of tokens generated
     * @param processingTimeMs Total processing time in milliseconds
     */
    fun recordRequest(
        nodeName: String,
        modelId: String,
        promptTokens: Int,
        completionTokens: Int,
        processingTimeMs: Long
    ) {
        // Increment total token counts
        tokenCountPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
            .addAndGet(completionTokens.toLong())

        tokenCountPerModel.computeIfAbsent(modelId) { AtomicLong(0) }
            .addAndGet(completionTokens.toLong())

        // Calculate tokens per second
        val tokensPerSecond = if (processingTimeMs > 0) {
            (completionTokens.toDouble() / processingTimeMs) * 1000.0
        } else {
            0.0
        }

        // Record generation speed
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

        // Record processing time
        processingTimePerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
            .addAndGet(processingTimeMs)

        processingTimePerModel.computeIfAbsent(modelId) { AtomicLong(0) }
            .addAndGet(processingTimeMs)

        // Update concurrent request tracking
        val currentConcurrent = currentConcurrentPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }

        // Decrement current concurrent count
        val newCount = currentConcurrent.decrementAndGet()

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

    /**
     * Records start of a request to track concurrent loads
     *
     * @param nodeName Name of the node processing the request
     */
    fun recordRequestStart(nodeName: String) {
        val currentConcurrent = currentConcurrentPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
        val newCount = currentConcurrent.incrementAndGet()

        // Update max concurrent if needed
        val maxConcurrent = maxConcurrentPerNode.computeIfAbsent(nodeName) { AtomicLong(0) }
        var currentMax = maxConcurrent.get()

        // Update max if current is larger
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

    /**
     * Gets the average generation speed for a node
     *
     * @param nodeName Name of the node
     * @return Average tokens per second, or 0.0 if no data
     */
    fun getAverageSpeedForNode(nodeName: String): Double {
        val speeds = tokensPerSecondPerNode[nodeName] ?: return 0.0
        return if (speeds.isEmpty()) 0.0 else speeds.average()
    }

    /**
     * Gets the average generation speed for a model
     *
     * @param modelId ID of the model
     * @return Average tokens per second, or 0.0 if no data
     */
    fun getAverageSpeedForModel(modelId: String): Double {
        val speeds = tokensPerSecondPerModel[modelId] ?: return 0.0
        return if (speeds.isEmpty()) 0.0 else speeds.average()
    }

    /**
     * Gets the current concurrent requests for a node
     *
     * @param nodeName Name of the node
     * @return Current number of concurrent requests
     */
    fun getCurrentConcurrentRequests(nodeName: String): Long {
        return currentConcurrentPerNode[nodeName]?.get() ?: 0
    }

    /**
     * Gets the maximum concurrent requests observed for a node
     *
     * @param nodeName Name of the node
     * @return Maximum number of concurrent requests observed
     */
    fun getMaxConcurrentRequests(nodeName: String): Long {
        return maxConcurrentPerNode[nodeName]?.get() ?: 0
    }

    /**
     * Gets all performance metrics as a map
     *
     * @return Map of performance metrics
     */
    fun getPerformanceMetrics(): Map<String, Any> {
        val metrics = mutableMapOf<String, Any>()

        // Node metrics
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

        // Model metrics
        val modelMetrics = mutableMapOf<String, Map<String, Any>>()
        tokenCountPerModel.forEach { (modelId, tokenCount) ->
            val modelMap = mutableMapOf<String, Any>()
            modelMap["totalTokens"] = tokenCount.get()
            modelMap["averageSpeed"] = getAverageSpeedForModel(modelId)
            modelMap["totalProcessingTime"] = processingTimePerModel[modelId]?.get() ?: 0
            modelMetrics[modelId] = modelMap
        }
        metrics["models"] = modelMetrics

        // Historical snapshots
        metrics["snapshots"] = weeklySnapshots.map { it.toMap() }

        return metrics
    }

    /**
     * Takes a snapshot of current metrics for historical tracking
     */
    private fun takeSnapshot() {
        val now = Instant.now().epochSecond
        val timeSinceLastSnapshot = now - lastSnapshotTime

        if (timeSinceLastSnapshot < 60 * 60) {
            // Don't take snapshots more often than hourly
            return
        }

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

        val snapshot = PerformanceSnapshot(
            timestamp = now,
            nodes = nodeSnapshots,
            models = modelSnapshots
        )

        weeklySnapshots.add(snapshot)
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

    /**
     * Cleans up old snapshots, keeping only the last week
     */
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

    /**
     * Data class for node snapshot data
     */
    data class NodeSnapshotData(
        val totalTokens: Long,
        val averageSpeed: Double,
        val maxConcurrent: Long
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf(
                "totalTokens" to totalTokens,
                "averageSpeed" to averageSpeed,
                "maxConcurrent" to maxConcurrent
            )
        }
    }

    /**
     * Data class for model snapshot data
     */
    data class ModelSnapshotData(
        val totalTokens: Long,
        val averageSpeed: Double
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf(
                "totalTokens" to totalTokens,
                "averageSpeed" to averageSpeed
            )
        }
    }

    /**
     * Data class for a performance snapshot
     */
    data class PerformanceSnapshot(
        val timestamp: Long,
        val nodes: Map<String, NodeSnapshotData>,
        val models: Map<String, ModelSnapshotData>
    ) {
        fun toMap(): Map<String, Any> {
            return mapOf(
                "timestamp" to timestamp,
                "nodes" to nodes.mapValues { it.value.toMap() },
                "models" to models.mapValues { it.value.toMap() }
            )
        }
    }
}
