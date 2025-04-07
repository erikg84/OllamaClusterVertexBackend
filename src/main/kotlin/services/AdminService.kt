package com.dallaslabs.services

import com.dallaslabs.models.*
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import mu.KotlinLogging
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Service for admin operations
 */
class AdminService(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val loadBalancer: LoadBalancerService,
    private val queue: Queue,
    logService: LogService
) {
    private val startTime = Instant.now()

    private val totalRequests = AtomicLong(0)
    private val successfulRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    private val requestLatencies = ConcurrentHashMap<String, Long>()

    init {
        vertx.setPeriodic(60000) {
            logger.debug { "Collecting metrics..." }
            // This would be expanded in a real implementation
        }
    }

    /**
     * Records a request
     */
    fun recordRequest(requestId: String, latency: Long, success: Boolean) {
        totalRequests.incrementAndGet()
        if (success) {
            successfulRequests.incrementAndGet()
        } else {
            failedRequests.incrementAndGet()
        }
        requestLatencies[requestId] = latency
    }

    /**
     * Gets general metrics
     */
    suspend fun getMetrics(): Metrics {
        logger.info { "Getting metrics" }

        val uptime = Instant.now().epochSecond - startTime.epochSecond
        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()

        val loadBalancingMetrics = try {
            loadBalancer.getNodeMetrics()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get load balancing metrics" }
            emptyMap()
        }

        return Metrics(
            uptime = uptime,
            totalRequests = totalRequests.get(),
            successfulRequests = successfulRequests.get(),
            failedRequests = failedRequests.get(),
            queueSize = queue.size(),
            activeNodes = nodeStatuses.count { it.status == "online" },
            totalNodes = nodeStatuses.size,
            loadBalancerNodeMetrics = loadBalancingMetrics
        )
    }

    /**
     * Gets Prometheus-formatted metrics
     */
    suspend fun getPrometheusMetrics(): String {
        logger.info { "Getting Prometheus metrics" }

        val metrics = getMetrics()
        val sb = StringBuilder()

        // Format metrics for Prometheus
        sb.appendLine("# HELP llm_cluster_uptime_seconds Total uptime in seconds")
        sb.appendLine("# TYPE llm_cluster_uptime_seconds counter")
        sb.appendLine("llm_cluster_uptime_seconds ${metrics.uptime}")

        sb.appendLine("# HELP llm_cluster_requests_total Total number of requests")
        sb.appendLine("# TYPE llm_cluster_requests_total counter")
        sb.appendLine("llm_cluster_requests_total ${metrics.totalRequests}")

        sb.appendLine("# HELP llm_cluster_successful_requests_total Total number of successful requests")
        sb.appendLine("# TYPE llm_cluster_successful_requests_total counter")
        sb.appendLine("llm_cluster_successful_requests_total ${metrics.successfulRequests}")

        sb.appendLine("# HELP llm_cluster_failed_requests_total Total number of failed requests")
        sb.appendLine("# TYPE llm_cluster_failed_requests_total counter")
        sb.appendLine("llm_cluster_failed_requests_total ${metrics.failedRequests}")

        sb.appendLine("# HELP llm_cluster_queue_size Current queue size")
        sb.appendLine("# TYPE llm_cluster_queue_size gauge")
        sb.appendLine("llm_cluster_queue_size ${metrics.queueSize}")

        sb.appendLine("# HELP llm_cluster_active_nodes Number of active nodes")
        sb.appendLine("# TYPE llm_cluster_active_nodes gauge")
        sb.appendLine("llm_cluster_active_nodes ${metrics.activeNodes}")

        sb.appendLine("# HELP llm_cluster_total_nodes Total number of nodes")
        sb.appendLine("# TYPE llm_cluster_total_nodes gauge")
        sb.appendLine("llm_cluster_total_nodes ${metrics.totalNodes}")

        return sb.toString()
    }

    /**
     * Gets request statistics
     */
    fun getRequestStatistics(): RequestStatistics {
        logger.info { "Getting request statistics" }

        // Calculate average latency
        var totalLatency = 0L
        var count = 0
        requestLatencies.values.forEach {
            totalLatency += it
            count++
        }

        val avgLatency = if (count > 0) totalLatency / count else 0

        return RequestStatistics(
            totalRequests = totalRequests.get(),
            successfulRequests = successfulRequests.get(),
            failedRequests = failedRequests.get(),
            averageLatency = avgLatency
        )
    }

    /**
     * Gets node health information
     */
    suspend fun getNodeHealth(): NodeHealth {
        logger.info { "Getting node health" }

        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()

        return NodeHealth(
            nodes = nodeStatuses.map {
                NodeHealthInfo(
                    name = it.node.name,
                    status = it.status,
                    message = it.message
                )
            }
        )
    }

    /**
     * Gets system information
     */
    fun getSystemInfo(): SystemInfo {
        logger.info { "Getting system information" }

        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024

        val memoryBean = ManagementFactory.getMemoryMXBean()
        val heapMemory = memoryBean.heapMemoryUsage
        val nonHeapMemory = memoryBean.nonHeapMemoryUsage

        return SystemInfo(
            javaVersion = System.getProperty("java.version"),
            osName = System.getProperty("os.name"),
            osVersion = System.getProperty("os.version"),
            availableProcessors = runtime.availableProcessors(),
            freeMemory = runtime.freeMemory() / mb,
            maxMemory = runtime.maxMemory() / mb,
            totalMemory = runtime.totalMemory() / mb,
            heapMemoryUsed = heapMemory.used / mb,
            heapMemoryMax = heapMemory.max / mb,
            nonHeapMemoryUsed = nonHeapMemory.used / mb,
            uptime = Instant.now().epochSecond - startTime.epochSecond
        )
    }

    /**
     * Resets statistics
     */
    fun resetStatistics() {
        logger.info { "Resetting statistics" }

        totalRequests.set(0)
        successfulRequests.set(0)
        failedRequests.set(0)
        requestLatencies.clear()
    }
}
