package com.dallaslabs.services

import com.dallaslabs.models.*
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for cluster operations
 */
class ClusterService(
    private val nodeService: NodeService
) {
    /**
     * Gets the overall cluster status
     */
    suspend fun getClusterStatus(): ClusterStatus {
        logger.info { "Getting cluster status" }

        // Get status of all nodes
        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()
        val onlineCount = nodeStatuses.count { it.status == "online" }
        val totalCount = nodeStatuses.size

        // Calculate metrics
        val availableGPUs = nodeStatuses
            .filter { it.status == "online" && it.node.type == "gpu" }
            .size

        val availableCPUs = nodeStatuses
            .filter { it.status == "online" && it.node.type == "cpu" }
            .size

        val status = ClusterStatus(
            totalNodes = totalCount,
            onlineNodes = onlineCount,
            offlineNodes = totalCount - onlineCount,
            availableGPUs = availableGPUs,
            availableCPUs = availableCPUs,
            status = if (onlineCount > 0) "healthy" else "degraded"
        )

        val uniqueModels = mutableSetOf<String>()
        var availableNodes = 0

        for (nodeStatus in nodeStatuses) {
            val nodeName = nodeStatus.node.name
            status.nodesStatus[nodeName] = nodeStatus.status

            if (nodeStatus.status == "online") {
                availableNodes++

                // Try to get models for online nodes
                try {
                    val models = nodeService.getNodeModels(nodeName).coAwait()
                    val modelIds = models.map { it.id }
                    status.modelsAvailable[nodeName] = modelIds
                    uniqueModels.addAll(modelIds)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get models for node: $nodeName" }
                    // Continue with other nodes
                }
            }
        }

        return status.copy(
            availableNodes = availableNodes,
            totalModels = uniqueModels.size
        )
    }

    /**
     * Gets all models available across the cluster
     */
    suspend fun getAllModels(): List<ModelInfo> {
        logger.info { "Getting all models in the cluster" }

        val models = mutableListOf<ModelInfo>()
        val modelSet = mutableSetOf<String>()

        // Get online nodes
        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()
        val onlineNodes = nodeStatuses.filter { it.status == "online" }.map { it.node }

        // Collect models from each node
        for (node in onlineNodes) {
            try {
                val nodeModels = nodeService.getNodeModels(node.name).coAwait()
                nodeModels.forEach { model ->
                    if (!modelSet.contains(model.id)) {
                        modelSet.add(model.id)
                        models.add(model)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get models from node: ${node.name}" }
                // Continue with other nodes
            }
        }

        return models
    }

    /**
     * Checks availability of a specific model
     */
    suspend fun checkModelAvailability(modelId: String): ModelAvailability {
        logger.info { "Checking availability of model: $modelId" }

        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()
        val onlineNodes = nodeStatuses.filter { it.status == "online" }.map { it.node }

        val availableNodes = mutableListOf<String>()

        // Check each node for the model
        for (node in onlineNodes) {
            try {
                val nodeModels = nodeService.getNodeModels(node.name).coAwait()
                if (nodeModels.any { it.id == modelId }) {
                    availableNodes.add(node.name)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to check model availability on node: ${node.name}" }
                // Continue with other nodes
            }
        }

        return ModelAvailability(
            modelId = modelId,
            available = availableNodes.isNotEmpty(),
            nodes = availableNodes
        )
    }

    /**
     * Gets metrics for all nodes in the cluster
     */
    suspend fun getClusterMetrics(): ClusterMetrics = coroutineScope {
        logger.info { "Getting cluster metrics" }

        val nodeMetrics = mutableMapOf<String, NodeMetrics>()
        val systemInfo = mutableMapOf<String, SystemInfo>()

        val nodes = nodeService.getNodes()
        val deferredMetrics = nodes.map { node ->
            async {
                try {
                    val metrics = nodeService.getNodeMetrics(node.name).coAwait()
                    val sysInfo = nodeService.getNodeSystemInfo(node.name).coAwait()

                    Triple(node.name, metrics, sysInfo)
                } catch (e: Exception) {
                    logger.warn { "Failed to get metrics for node: ${node.name}: ${e.message}" }
                    null
                }
            }
        }

        val results = deferredMetrics.awaitAll().filterNotNull()

        for ((nodeName, metrics, sysInfo) in results) {
            nodeMetrics[nodeName] = metrics
            systemInfo[nodeName] = sysInfo
        }

        ClusterMetrics(
            nodeMetrics = nodeMetrics,
            systemInfo = systemInfo
        )
    }

    /**
     * Resets statistics for all nodes in the cluster
     */
    suspend fun resetClusterStats(): List<String> = coroutineScope {
        logger.info { "Resetting cluster stats" }

        val nodes = nodeService.getNodes()
        val deferredResets = nodes.map { node ->
            async {
                try {
                    nodeService.resetNodeStats(node.name).coAwait()
                    null  // Success - no error
                } catch (e: Exception) {
                    "${node.name}: ${e.message}"  // Return error message
                }
            }
        }

        val results = deferredResets.awaitAll()
        results.filterNotNull()  // Return only error messages
    }

    /**
     * Gets logs from all nodes in the cluster
     */
    suspend fun getClusterLogs(level: String? = null): Map<String, List<Map<String, Any>>> = coroutineScope {
        logger.info { "Getting cluster logs, level: $level" }

        val clusterLogs = mutableMapOf<String, List<Map<String, Any>>>()

        val nodes = nodeService.getNodes()
        val deferredLogs = nodes.map { node ->
            async {
                try {
                    val logs = nodeService.getNodeLogs(node.name, level).coAwait()
                    node.name to logs
                } catch (e: Exception) {
                    logger.warn { "Failed to get logs for node: ${node.name}: ${e.message}" }
                    null
                }
            }
        }

        val results = deferredLogs.awaitAll().filterNotNull()

        for ((nodeName, logs) in results) {
            clusterLogs[nodeName] = logs
        }

        clusterLogs
    }
}
