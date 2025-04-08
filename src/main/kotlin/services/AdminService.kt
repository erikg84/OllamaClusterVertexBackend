package com.dallaslabs.services

import com.dallaslabs.models.*
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

class AdminService(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val loadBalancer: LoadBalancerService,
    private val queue: Queue,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {
    private val startTime = Instant.now()

    private val totalRequests = AtomicLong(0)
    private val successfulRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    private val requestLatencies = ConcurrentHashMap<String, Long>()

    init {
        vertx.setPeriodic(60000) {
            logger.debug { "Collecting metrics..." }
            launch {
                logService.log("debug", "Periodic metric collection running", emptyMap())
            }
        }
    }

    fun recordRequest(requestId: String, latency: Long, success: Boolean) {
        totalRequests.incrementAndGet()
        if (success) {
            successfulRequests.incrementAndGet()
        } else {
            failedRequests.incrementAndGet()
        }
        requestLatencies[requestId] = latency

        launch {
            logService.log("debug", "Request recorded", mapOf(
                "requestId" to requestId,
                "latency" to latency,
                "success" to success
            ))
        }
    }

    suspend fun getMetrics(): Metrics {
        logger.info { "Getting metrics" }
        launch { logService.log("info", "Fetching cluster metrics", emptyMap()) }

        val uptime = Instant.now().epochSecond - startTime.epochSecond
        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()

        val loadBalancingMetrics = try {
            loadBalancer.getNodeMetrics()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get load balancing metrics" }
            launch { logService.logError("Failed to get load balancer metrics", e) }
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

    suspend fun getPrometheusMetrics(): String {
        logger.info { "Getting Prometheus metrics" }
        launch { logService.log("info", "Fetching Prometheus metrics", emptyMap()) }

        val metrics = getMetrics()
        val sb = StringBuilder()

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

    fun getRequestStatistics(): RequestStatistics {
        logger.info { "Getting request statistics" }
        launch {
            logService.log("debug", "Retrieving request statistics", emptyMap())
        }

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

    suspend fun getNodeHealth(): NodeHealth {
        logger.info { "Getting node health" }
        launch { logService.log("info", "Fetching node health info", emptyMap()) }

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

    fun getSystemInfo(): SystemInfo {
        logger.info { "Getting system information" }
        launch {
            logService.log("info", "Fetching system info", emptyMap())
        }

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

    fun resetStatistics() {
        logger.info { "Resetting statistics" }
        totalRequests.set(0)
        successfulRequests.set(0)
        failedRequests.set(0)
        requestLatencies.clear()

        launch {
            logService.log("info", "Statistics reset", emptyMap())
        }
    }
}
