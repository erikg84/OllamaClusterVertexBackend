package com.dallaslabs.models

/**
 * Represents cluster status
 */
data class ClusterStatus(
    val totalNodes: Int = 0,
    val availableNodes: Int = 0,
    val totalModels: Int = 0,
    val nodesStatus: MutableMap<String, String> = mutableMapOf(),
    val nodeQueueSizes: Map<String, Int> = emptyMap(),
    val modelsAvailable: MutableMap<String, List<String>> = mutableMapOf(),
    val onlineNodes: Int = 0,
    val offlineNodes: Int = 0,
    val availableGPUs: Int = 0,
    val availableCPUs: Int = 0,
    val status: String = ""
)

/**
 * Represents model information
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val type: String = "unknown",
    val size: Long = 0,
    val quantization: String = "unknown"
)

/**
 * Represents model availability
 */
data class ModelAvailability(
    val modelId: String,
    val available: Boolean,
    val nodes: List<String>
)

/**
 * Represents metrics
 */
data class Metrics(
    val uptime: Long,
    val totalRequests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val queueSize: Int,
    val activeNodes: Int,
    val totalNodes: Int
)

/**
 * Represents request statistics
 */
data class RequestStatistics(
    val totalRequests: Long,
    val successfulRequests: Long,
    val failedRequests: Long,
    val averageLatency: Long
)

/**
 * Represents node health
 */
data class NodeHealth(
    val nodes: List<NodeHealthInfo>
)

/**
 * Represents node health information
 */
data class NodeHealthInfo(
    val name: String,
    val status: String,
    val message: String?
)

/**
 * Represents system information
 */
data class SystemInfo(
    val javaVersion: String,
    val osName: String,
    val osVersion: String,
    val availableProcessors: Int,
    val freeMemory: Long,
    val maxMemory: Long,
    val totalMemory: Long,
    val heapMemoryUsed: Long,
    val heapMemoryMax: Long,
    val nonHeapMemoryUsed: Long,
    val apiVersion: String = "",
    val uptime: Long = 0,
    val cpuUsage: Double = 0.0,
    val memoryUsage: MemoryUsage = MemoryUsage(),
    val nodeJsVersion: String = "",
    val environment: String = ""
)

/**
 * Represents memory usage information
 */
data class MemoryUsage(
    val used: String = "",
    val total: String = ""
)

/**
 * Represents performance metrics for a node
 */
data class NodePerf(
    val avgResponseTime: Double = 0.0,
    val requestsProcessed: Long = 0,
    val errorRate: Double = 0.0
)

/**
 * Represents performance metrics for a model
 */
data class ModelPerf(
    val avgResponseTime: Double = 0.0,
    val requestsProcessed: Long = 0,
    val avgTokensGenerated: Double = 0.0
)

/**
 * Represents a time point for time series data
 */
data class TimePoint(
    val time: String = "",
    val value: Double = 0.0
)

/**
 * Represents metrics for a node
 */
data class NodeMetrics(
    val requestCounts: Map<String, Long> = emptyMap(),
    val nodePerformance: Map<String, NodePerf> = emptyMap(),
    val modelPerformance: Map<String, ModelPerf> = emptyMap(),
    val responseTimes: Map<String, List<TimePoint>> = emptyMap()
)

/**
 * Represents aggregated metrics across the cluster
 */
data class ClusterMetrics(
    val nodeMetrics: Map<String, NodeMetrics> = emptyMap(),
    val systemInfo: Map<String, SystemInfo> = emptyMap()
)
