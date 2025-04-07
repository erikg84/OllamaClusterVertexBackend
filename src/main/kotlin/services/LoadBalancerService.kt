package com.dallaslabs.services

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Service for balancing load across nodes
 */
class LoadBalancerService(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val modelRegistry: ModelRegistryService,
    private val performanceTracker: PerformanceTrackerService,
    logService: LogService
): CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    // Track request counts per node
    private val requestCounts = ConcurrentHashMap<String, AtomicInteger>()

    // Track recent response times per node (in ms)
    private val responseTimesMs = ConcurrentHashMap<String, MutableList<Long>>()

    // Maximum response times to keep per node
    private val MAX_RESPONSE_TIMES = 10

    // Track failures per node
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()

    // Scoring weights for different factors
    private val LOAD_WEIGHT = 0.4
    private val RESPONSE_TIME_WEIGHT = 0.3
    private val FAILURE_WEIGHT = 0.3

    init {
        // Initialize with empty data for all nodes
        nodeService.getNodes().forEach { node ->
            requestCounts[node.name] = AtomicInteger(0)
            responseTimesMs[node.name] = mutableListOf()
            failureCounts[node.name] = AtomicInteger(0)
        }

        // Reset failure counts periodically (every 10 minutes)
        vertx.setPeriodic(10 * 60 * 1000) {
            failureCounts.forEach { (_, count) -> count.set(0) }
            logger.info { "Reset failure counts for all nodes" }
        }
    }

    /**
     * Selects the best node for a model based on load, performance, and capability
     *
     * @param modelId The model ID
     * @param preferredNodeType Optional preferred node type (e.g., "gpu" or "cpu")
     * @return The name of the best node, or null if no suitable node is found
     */
    suspend fun selectBestNodeForModel(modelId: String, preferredNodeType: String? = null): String? {

        val availableNodes = modelRegistry.getNodesForModel(modelId)
        if (availableNodes.isEmpty()) {
            logger.warn { "No nodes available for model: $modelId" }
            return null
        }

        // Get node objects
        val nodes = nodeService.getNodes().filter { it.name in availableNodes }

        // Get current load for each node
        val nodeLoads = nodeService.getNodeLoads()

        // Calculate scores for each node
        val nodeScores = mutableMapOf<String, Double>()

        for (node in nodes) {
            // Skip nodes that don't match preferred type if specified
            if (preferredNodeType != null && node.type != preferredNodeType) {
                continue
            }

            // Calculate load score (lower load = higher score)
            val load = nodeLoads[node.name] ?: Double.MAX_VALUE
            val loadScore = if (load == Double.MAX_VALUE) 0.0 else 1.0 / (1.0 + load)

            // Calculate response time score (lower average time = higher score)
            val responseTimes = responseTimesMs[node.name] ?: mutableListOf()
            val avgResponseTime = if (responseTimes.isEmpty()) 1000.0 else responseTimes.average()
            val responseTimeScore = 1.0 / (1.0 + avgResponseTime / 1000.0)

            // Calculate failure score (fewer failures = higher score)
            val failures = failureCounts[node.name]?.get() ?: 0
            val failureScore = 1.0 / (1.0 + failures)

            // Calculate performance score (higher speed = higher score)
            val avgSpeed = performanceTracker.getAverageSpeedForNode(node.name)
            val speedScore = if (avgSpeed <= 0.0) 0.5 else Math.min(1.0, avgSpeed / 20.0) // Cap at 20 tokens/sec

            // Calculate concurrent request score (fewer concurrent = higher score)
            val concurrentRequests = performanceTracker.getCurrentConcurrentRequests(node.name)
            val concurrentScore = 1.0 / (1.0 + concurrentRequests)

            // Calculate hardware capability score
            val capabilityScore = when {
                modelId.contains("32b") && node.type == "gpu" -> 1.0 // Large models prefer GPU
                modelId.contains("16b") && node.type == "gpu" -> 0.9 // Medium models prefer GPU but can run on CPU
                modelId.contains("7b") && node.type == "cpu" -> 0.8  // Small models are fine on CPU
                else -> 0.7 // Default score
            }

            // Calculate additional capability score based on specific capabilities
            val additionalCapabilityScore = when {
                node.capabilities.contains("tensor") && (modelId.contains("llama") || modelId.contains("mistral")) -> 0.3
                node.capabilities.contains("metal") && node.platform == "macos" -> 0.2
                node.capabilities.contains("parallel") && node.type == "cpu" -> 0.1
                else -> 0.0
            }

            // Calculate total score with weighted components
            val totalScore = (LOAD_WEIGHT * loadScore) +
                    (RESPONSE_TIME_WEIGHT * responseTimeScore) +
                    (FAILURE_WEIGHT * failureScore) +
                    (0.2 * speedScore) +  // Add speed factor
                    (0.2 * concurrentScore) +  // Add concurrency factor
                    (0.1 * capabilityScore) +  // Add capability factor
                    (0.1 * additionalCapabilityScore)  // Add specific capabilities factor

            nodeScores[node.name] = totalScore

            logger.debug {
                "Node ${node.name} score: $totalScore (load=$loadScore, " +
                        "responseTime=$responseTimeScore, failures=$failureScore, " +
                        "speed=$speedScore, concurrent=$concurrentScore, " +
                        "capability=$capabilityScore, additionalCapability=$additionalCapabilityScore)"
            }
        }

        // If no nodes match the preferred type, use all available nodes
        if (nodeScores.isEmpty() && preferredNodeType != null) {
            logger.info { "No $preferredNodeType nodes available, falling back to any node type" }
            return selectBestNodeForModel(modelId, null)
        }

        // Return the node with the highest score
        return nodeScores.maxByOrNull { it.value }?.key
    }

    /**
     * Records a successful request to a node
     *
     * @param nodeName The node name
     * @param responseTimeMs The response time in milliseconds
     */
    fun recordSuccess(nodeName: String, responseTimeMs: Long) {
        requestCounts.computeIfAbsent(nodeName) { AtomicInteger(0) }.incrementAndGet()

        // Update response times
        val times = responseTimesMs.computeIfAbsent(nodeName) { mutableListOf() }
        synchronized(times) {
            times.add(responseTimeMs)
            // Keep only the most recent times
            if (times.size > MAX_RESPONSE_TIMES) {
                times.removeAt(0)
            }
        }

        logger.debug { "Recorded successful request to $nodeName (${responseTimeMs}ms)" }
    }

    /**
     * Records a failed request to a node
     *
     * @param nodeName The node name
     */
    fun recordFailure(nodeName: String) {
        failureCounts.computeIfAbsent(nodeName) { AtomicInteger(0) }.incrementAndGet()
        logger.debug { "Recorded failure for node $nodeName" }
    }

    /**
     * Gets performance metrics for all nodes
     *
     * @return Map of node names to their metrics
     */
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

        return metrics
    }

    /**
     * Node performance metrics
     */
    data class LoadBalancerNodeMetrics(
        val requestCount: Int,
        val averageResponseTimeMs: Double,
        val failureCount: Int
    )
}
