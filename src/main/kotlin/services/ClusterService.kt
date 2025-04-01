package com.dallaslabs.services

import com.dallaslabs.models.ClusterStatus
import com.dallaslabs.models.ModelAvailability
import com.dallaslabs.models.ModelInfo
import com.dallaslabs.models.Node
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for cluster operations
 */
class ClusterService(
    private val vertx: Vertx,
    private val nodes: List<Node>,
    private val nodeService: NodeService
) {
    private val webClient = WebClient.create(
        vertx, WebClientOptions()
            .setConnectTimeout(5000)
            .setIdleTimeout(10000)
    )

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

        return ClusterStatus(
            totalNodes = totalCount,
            onlineNodes = onlineCount,
            offlineNodes = totalCount - onlineCount,
            availableGPUs = availableGPUs,
            availableCPUs = availableCPUs,
            status = if (onlineCount > 0) "healthy" else "degraded"
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
}
