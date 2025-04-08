package com.dallaslabs.services

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

class LoadBalancerService(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val modelRegistry: ModelRegistryService,
    private val performanceTracker: PerformanceTrackerService,
    private val logService: LogService
): CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val responseTimesMs = ConcurrentHashMap<String, MutableList<Long>>()
    private val MAX_RESPONSE_TIMES = 10
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()

    private val LOAD_WEIGHT = 0.4
    private val RESPONSE_TIME_WEIGHT = 0.3
    private val FAILURE_WEIGHT = 0.3

    init {
        nodeService.getNodes().forEach { node ->
            requestCounts[node.name] = AtomicInteger(0)
            responseTimesMs[node.name] = mutableListOf()
            failureCounts[node.name] = AtomicInteger(0)
        }

        vertx.setPeriodic(10 * 60 * 1000) {
            failureCounts.forEach { (node, count) ->
                count.set(0)
                launch {
                    logService.log("info", "Reset failure count for node", mapOf("nodeName" to node))
                }
            }
            logger.info { "Reset failure counts for all nodes" }
        }
    }

    suspend fun selectBestNodeForModel(modelId: String, preferredNodeType: String? = null): String? {
        val availableNodes = modelRegistry.getNodesForModel(modelId)
        if (availableNodes.isEmpty()) {
            logger.warn { "No nodes available for model: $modelId" }
            logService.log("warn", "No nodes available for model", mapOf("modelId" to modelId))
            return null
        }

        val nodes = nodeService.getNodes().filter { it.name in availableNodes }
        val nodeLoads = nodeService.getNodeLoads()

        val nodeScores = mutableMapOf<String, Double>()

        for (node in nodes) {
            if (preferredNodeType != null && node.type != preferredNodeType) continue

            val load = nodeLoads[node.name] ?: Double.MAX_VALUE
            val loadScore = if (load == Double.MAX_VALUE) 0.0 else 1.0 / (1.0 + load)

            val responseTimes = responseTimesMs[node.name] ?: mutableListOf()
            val avgResponseTime = if (responseTimes.isEmpty()) 1000.0 else responseTimes.average()
            val responseTimeScore = 1.0 / (1.0 + avgResponseTime / 1000.0)

            val failures = failureCounts[node.name]?.get() ?: 0
            val failureScore = 1.0 / (1.0 + failures)

            val avgSpeed = performanceTracker.getAverageSpeedForNode(node.name)
            val speedScore = if (avgSpeed <= 0.0) 0.5 else Math.min(1.0, avgSpeed / 20.0)

            val concurrentRequests = performanceTracker.getCurrentConcurrentRequests(node.name)
            val concurrentScore = 1.0 / (1.0 + concurrentRequests)

            val capabilityScore = when {
                modelId.contains("32b") && node.type == "gpu" -> 1.0
                modelId.contains("16b") && node.type == "gpu" -> 0.9
                modelId.contains("7b") && node.type == "cpu" -> 0.8
                else -> 0.7
            }

            val additionalCapabilityScore = when {
                node.capabilities.contains("tensor") && (modelId.contains("llama") || modelId.contains("mistral")) -> 0.3
                node.capabilities.contains("metal") && node.platform == "macos" -> 0.2
                node.capabilities.contains("parallel") && node.type == "cpu" -> 0.1
                else -> 0.0
            }

            val totalScore = (LOAD_WEIGHT * loadScore) +
                    (RESPONSE_TIME_WEIGHT * responseTimeScore) +
                    (FAILURE_WEIGHT * failureScore) +
                    (0.2 * speedScore) +
                    (0.2 * concurrentScore) +
                    (0.1 * capabilityScore) +
                    (0.1 * additionalCapabilityScore)

            nodeScores[node.name] = totalScore

            logger.debug {
                "Node ${node.name} score: $totalScore (load=$loadScore, responseTime=$responseTimeScore, failures=$failureScore, speed=$speedScore, concurrent=$concurrentScore, capability=$capabilityScore, additionalCapability=$additionalCapabilityScore)"
            }
        }

        if (nodeScores.isEmpty() && preferredNodeType != null) {
            logger.info { "No $preferredNodeType nodes available, falling back to any node type" }
            logService.log("info", "Fallback to any node type", mapOf("modelId" to modelId))
            return selectBestNodeForModel(modelId, null)
        }

        val bestNode = nodeScores.maxByOrNull { it.value }?.key
        logService.log("info", "Selected best node for model", mapOf("modelId" to modelId, "nodeName" to (bestNode ?: "none")))
        return bestNode
    }

    fun recordSuccess(nodeName: String, responseTimeMs: Long) {
        requestCounts.computeIfAbsent(nodeName) { AtomicInteger(0) }.incrementAndGet()
        val times = responseTimesMs.computeIfAbsent(nodeName) { mutableListOf() }
        synchronized(times) {
            times.add(responseTimeMs)
            if (times.size > MAX_RESPONSE_TIMES) times.removeAt(0)
        }
        logger.debug { "Recorded successful request to $nodeName (${responseTimeMs}ms)" }
        launch { logService.log("debug", "Recorded success", mapOf("nodeName" to nodeName, "responseTimeMs" to responseTimeMs)) }
    }

    fun recordFailure(nodeName: String) {
        failureCounts.computeIfAbsent(nodeName) { AtomicInteger(0) }.incrementAndGet()
        logger.debug { "Recorded failure for node $nodeName" }
        launch { logService.log("warn", "Recorded failure for node", mapOf("nodeName" to nodeName)) }
    }

    fun getNodeMetrics(): Map<String, LoadBalancerNodeMetrics> {
        val metrics = mutableMapOf<String, LoadBalancerNodeMetrics>()
        nodeService.getNodes().forEach { node ->
            val requestCount = requestCounts[node.name]?.get() ?: 0
            val responseTimes = responseTimesMs[node.name] ?: mutableListOf()
            val avgResponseTime = if (responseTimes.isEmpty()) 0.0 else responseTimes.average()
            val failureCount = failureCounts[node.name]?.get() ?: 0

            metrics[node.name] = LoadBalancerNodeMetrics(
                requestCount = requestCount,
                averageResponseTimeMs = avgResponseTime,
                failureCount = failureCount
            )
        }
        launch { logService.log("debug", "Retrieved node metrics", mapOf("nodeCount" to metrics.size)) }
        return metrics
    }

    data class LoadBalancerNodeMetrics(
        val requestCount: Int,
        val averageResponseTimeMs: Double,
        val failureCount: Int
    )
}
