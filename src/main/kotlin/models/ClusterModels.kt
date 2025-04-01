package com.dallaslabs.models

/**
 * Represents cluster status
 */
data class ClusterStatus(
    val totalNodes: Int,
    val onlineNodes: Int,
    val offlineNodes: Int,
    val availableGPUs: Int,
    val availableCPUs: Int,
    val status: String
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
    val uptime: Long
)
