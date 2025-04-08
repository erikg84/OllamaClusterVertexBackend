package com.dallaslabs.services

import com.dallaslabs.models.*
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class ClusterService(
    private val nodeService: NodeService,
    private val modelRegistry: ModelRegistryService,
    private val vertx: Vertx,
    private val logService: LogService
): CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    init {
        launch {
            modelRegistry.refreshRegistry()
        }
    }

    suspend fun getClusterStatus(): ClusterStatus {
        logger.info { "Getting cluster status" }
        logService.log("info", "Getting cluster status", emptyMap())

        val nodeStatuses = nodeService.getAllNodesStatus().coAwait()
        val onlineCount = nodeStatuses.count { it.status == "online" }
        val totalCount = nodeStatuses.size

        val availableGPUs = nodeStatuses.filter { it.status == "online" && it.node.type == "gpu" }.size
        val availableCPUs = nodeStatuses.filter { it.status == "online" && it.node.type == "cpu" }.size

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
                    val modelIds = modelRegistry.getAllModels().filter { it.node == nodeName }.map { it.id }
                    status.modelsAvailable[nodeName] = modelIds
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get models for node: $nodeName" }
                    logService.logError("Failed to get models for node", e, mapOf("nodeName" to nodeName))
                }
            }
        }

        logService.log("info", "Cluster status retrieved", mapOf(
            "onlineNodes" to onlineCount,
            "totalNodes" to totalCount,
            "availableGPUs" to availableGPUs,
            "availableCPUs" to availableCPUs,
            "totalModels" to uniqueModels.size
        ))

        return status.copy(
            availableNodes = onlineCount,
            totalModels = uniqueModels.size
        )
    }

    fun getAllModels(): List<ModelInfo> {
        logger.info { "Getting all models in the cluster" }
        launch {
            logService.log("debug", "Retrieving all cluster models", emptyMap())
        }
        return modelRegistry.getAllModels()
    }

    fun checkModelAvailability(modelId: String): ModelAvailability {
        logger.info { "Checking availability of model: $modelId" }
        val availableNodes = modelRegistry.getNodesForModel(modelId)

        launch {
            logService.log("debug", "Checked model availability", mapOf("modelId" to modelId, "available" to availableNodes.isNotEmpty()))
        }

        return ModelAvailability(
            modelId = modelId,
            available = availableNodes.isNotEmpty(),
            nodes = availableNodes
        )
    }

    suspend fun getClusterMetrics(): ClusterMetrics = coroutineScope {
        logger.info { "Getting cluster metrics" }
        logService.log("info", "Retrieving cluster metrics", emptyMap())

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
                    logService.logError("Failed to get metrics for node", e, mapOf("nodeName" to node.name))
                    null
                }
            }
        }

        val results = deferredMetrics.awaitAll().filterNotNull()
        for ((nodeName, metrics, sysInfo) in results) {
            nodeMetrics[nodeName] = metrics
            systemInfo[nodeName] = sysInfo
        }

        logService.log("info", "Cluster metrics collected", mapOf("nodeCount" to results.size))

        ClusterMetrics(
            nodeMetrics = nodeMetrics,
            systemInfo = systemInfo
        )
    }

    suspend fun resetClusterStats(): List<String> = coroutineScope {
        logger.info { "Resetting cluster stats" }
        logService.log("info", "Resetting statistics for all cluster nodes", emptyMap())

        val nodes = nodeService.getNodes()
        val deferredResets = nodes.map { node ->
            async {
                try {
                    nodeService.resetNodeStats(node.name).coAwait()
                    null
                } catch (e: Exception) {
                    logger.warn { "Failed to reset stats for node: ${node.name}: ${e.message}" }
                    logService.logError("Failed to reset stats for node", e, mapOf("nodeName" to node.name))
                    "${node.name}: ${e.message}"
                }
            }
        }

        val results = deferredResets.awaitAll()
        logService.log("info", "Completed resetting stats", mapOf("errors" to results.filterNotNull().size))
        results.filterNotNull()
    }

    suspend fun getClusterLogs(level: String? = null): Map<String, List<Map<String, Any>>> = coroutineScope {
        logger.info { "Getting cluster logs, level: $level" }
        logService.log("info", "Retrieving cluster logs", mapOf("logLevel" to (level ?: "all")))

        val clusterLogs = mutableMapOf<String, List<Map<String, Any>>>()

        val nodes = nodeService.getNodes()
        val deferredLogs = nodes.map { node ->
            async {
                try {
                    val logs = nodeService.getNodeLogs(node.name, level).coAwait()
                    node.name to logs
                } catch (e: Exception) {
                    logger.warn { "Failed to get logs for node: ${node.name}: ${e.message}" }
                    logService.logError("Failed to retrieve logs from node", e, mapOf("nodeName" to node.name))
                    null
                }
            }
        }

        val results = deferredLogs.awaitAll().filterNotNull()
        for ((nodeName, logs) in results) {
            clusterLogs[nodeName] = logs
        }

        logService.log("info", "Cluster logs collected", mapOf("nodeCount" to results.size))
        clusterLogs
    }
}
