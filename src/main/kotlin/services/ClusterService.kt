package com.dallaslabs.services

import com.dallaslabs.models.*
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Service for cluster operations
 */
class ClusterService(
    private val nodeService: NodeService,
    private val modelRegistry: ModelRegistryService,
    private val vertx: Vertx,
    logService: LogService
): CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    init {
        launch {
            modelRegistry.refreshRegistry()
        }
    }

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

        val uniqueModels = modelRegistry.getAllModels().map { it.id }.toSet()
        for (nodeStatus in nodeStatuses) {
            val nodeName = nodeStatus.node.name
            status.nodesStatus[nodeName] = nodeStatus.status

            if (nodeStatus.status == "online") {
                try {
                    val modelIds = modelRegistry.getAllModels()
                        .filter { it.node == nodeName }
                        .map { it.id }

                    status.modelsAvailable[nodeName] = modelIds
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get models for node: $nodeName" }
                    // Continue with other nodes
                }
            }
        }

        return status.copy(
            availableNodes = onlineCount,
            totalModels = uniqueModels.size
        )
    }

    /**
     * Gets all models available across the cluster
     */
    fun getAllModels(): List<ModelInfo> {
        logger.info { "Getting all models in the cluster" }
        return modelRegistry.getAllModels()
    }

    /**
     * Checks availability of a specific model
     */
    fun checkModelAvailability(modelId: String): ModelAvailability {
        logger.info { "Checking availability of model: $modelId" }
        val availableNodes = modelRegistry.getNodesForModel(modelId)

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
