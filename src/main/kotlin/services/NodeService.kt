package com.dallaslabs.services

import com.dallaslabs.models.*
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for managing LLM nodes
 */
class NodeService(
    vertx: Vertx,
    private val nodes: List<Node>,
    private val logService: LogService
): CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val webClient = WebClient.create(vertx, WebClientOptions()
        .setConnectTimeout(5000)
        .setIdleTimeout(10000)
    )

    /**
     * Gets the models available on a node
     *
     * @param nodeName Name of the node
     * @return Future with list of models
     */
    suspend fun getNodeModels(nodeName: String): Future<List<ModelInfo>> {
        val node = nodes.find { it.name == nodeName }
            ?: return Future.failedFuture("Node $nodeName not found")

        logger.info { "Getting models for node: ${node.name}" }
        logService.log("info", "Retrieving node models", mapOf(
            "nodeName" to node.name,
            "nodeHost" to node.host
        ))
        return try {
            val response = webClient.get(node.port, node.host, "/models")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                // First try to parse as an array
                try {
                    val models = response.bodyAsJsonArray().map { json ->
                        val modelJson = json as JsonObject
                        ModelInfo(
                            id = modelJson.getString("id"),
                            name = modelJson.getString("name"),
                            type = modelJson.getString("type", "unknown"),
                            size = modelJson.getLong("size", 0),
                            quantization = modelJson.getString("quantization", "unknown")
                        )
                    }
                    logService.log("info", "Successfully retrieved models", mapOf(
                        "nodeName" to node.name,
                        "modelCount" to models.size
                    ))
                    Future.succeededFuture(models)
                } catch (e: Exception) {
                    // If that fails, try to parse as an object with a models field
                    try {
                        val jsonObject = response.bodyAsJsonObject()
                        val models = if (jsonObject.containsKey("models")) {
                            jsonObject.getJsonArray("models").map { json ->
                                val modelJson = json as JsonObject
                                ModelInfo(
                                    id = modelJson.getString("id", "unknown"),
                                    name = modelJson.getString("name", "unknown"),
                                    type = modelJson.getString("type", "unknown"),
                                    size = modelJson.getLong("size", 0),
                                    quantization = modelJson.getString("quantization", "unknown")
                                )
                            }
                        } else {
                            emptyList()
                        }
                        logService.log("info", "Successfully parsed models from alternate format", mapOf(
                            "nodeName" to node.name,
                            "modelCount" to models.size
                        ))
                        Future.succeededFuture(models)
                    } catch (e2: Exception) {
                        logService.logError("Failed to parse models response", e2, mapOf(
                            "nodeName" to node.name,
                            "responseBody" to response.bodyAsString()
                        ))
                        logger.error(e2) { "Failed to parse models response for node: ${node.name}" }
                        Future.failedFuture("Failed to parse models response: ${e2.message}")
                    }
                }
            } else {
                logService.log("warn", "Failed to get models", mapOf(
                    "nodeName" to node.name,
                    "statusCode" to response.statusCode()
                ))
                Future.failedFuture("Failed to get models with status code: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get models for node: ${node.name}" }
            logService.logError("Failed to get models for node", e, mapOf(
                "nodeName" to node.name
            ))
            Future.failedFuture(e)
        }
    }

    /**
     * Returns all nodes
     */
    fun getNodes(): List<Node> {
        launch {
            logService.log("debug", "Retrieved all nodes", mapOf(
                "nodeCount" to nodes.size
            ))
        }
        return nodes
    }

    /**
     * Gets the status of a node
     *
     * @param nodeName Name of the node
     * @return Future with the node status
     */
    suspend fun getNodeStatus(nodeName: String): Future<NodeStatus> {
        val node = nodes.find { it.name == nodeName }
            ?: return Future.failedFuture("Node $nodeName not found")

        logger.info { "Checking status of node: ${node.name}" }
        logService.log("info", "Checking node status", mapOf(
            "nodeName" to node.name,
            "nodeHost" to node.host
        ))
        return try {
            val response = webClient.get(node.port, node.host, "/health")
                .timeout(5000)
                .send()
                .coAwait()

            val status = if (response.statusCode() == 200) {
                logService.log("info", "Node is online", mapOf(
                    "nodeName" to node.name
                ))
                NodeStatus(
                    node = node,
                    status = "online"
                )
            } else {
                logService.log("warn", "Node health check failed", mapOf(
                    "nodeName" to node.name,
                    "statusCode" to response.statusCode()
                ))
                NodeStatus(
                    node = node,
                    status = "error",
                    message = "Health check failed with status code: ${response.statusCode()}"
                )
            }

            Future.succeededFuture(status)
        } catch (e: Exception) {
            logger.error(e) { "Failed to check node health: ${node.name}" }
            logService.logError("Failed to check node health", e, mapOf(
                "nodeName" to node.name
            ))
            Future.succeededFuture(
                NodeStatus(
                    node = node,
                    status = "offline",
                    message = e.message
                )
            )
        }
    }

    /**
     * Gets the status of all nodes
     *
     * @return Future with a list of node statuses
     */
    suspend fun getAllNodesStatus(): Future<List<NodeStatus>> {
        logger.info { "Getting status of all nodes" }
        logService.log("info", "Retrieving status for all nodes", mapOf(
            "totalNodes" to nodes.size
        ))

        val statuses = mutableListOf<NodeStatus>()

        for (node in nodes) {
            try {
                val status = getNodeStatus(node.name).coAwait()
                statuses.add(status)
            } catch (e: Exception) {
                logger.error(e) { "Failed to get status of node: ${node.name}" }
                logService.logError("Failed to get status of node", e, mapOf(
                    "nodeName" to node.name
                ))
            }
        }
        logService.log("info", "Completed node status retrieval", mapOf(
            "onlineNodes" to statuses.count { it.status == "online" },
            "totalNodes" to statuses.size
        ))
        return Future.succeededFuture(statuses)
    }

    /**
     * Gets metrics for a specific node
     *
     * @param nodeName Name of the node
     * @return Future with node metrics
     */
    suspend fun getNodeMetrics(nodeName: String): Future<NodeMetrics> {
        val node = nodes.find { it.name == nodeName }
            ?: return Future.failedFuture("Node $nodeName not found")

        logger.info { "Retrieving metrics for node: ${node.name}" }
        logService.log("info", "Retrieving node metrics", mapOf("nodeName" to node.name))

        return try {
            val response = webClient.get(node.port, node.host, "/admin/metrics")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                val metrics = response.bodyAsJson(NodeMetrics::class.java)
                logService.log("info", "Successfully retrieved node metrics", mapOf("nodeName" to node.name))
                Future.succeededFuture(metrics)
            } else {
                logService.log("warn", "Failed to retrieve metrics", mapOf("nodeName" to node.name, "statusCode" to response.statusCode()))
                Future.failedFuture("Metrics retrieval failed: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Metrics retrieval failed for node: ${node.name}" }
            logService.logError("Exception retrieving node metrics", e, mapOf("nodeName" to node.name))
            Future.failedFuture(e)
        }
    }

    /**
     * Gets system information for a specific node
     *
     * @param nodeName Name of the node
     * @return Future with system information
     */
    suspend fun getNodeSystemInfo(nodeName: String): Future<SystemInfo> {
        val node = nodes.find { it.name == nodeName }
            ?: return Future.failedFuture("Node $nodeName not found")

        logger.info { "Getting system info for node: ${node.name}" }
        logService.log("info", "Retrieving system info", mapOf("nodeName" to node.name))

        return try {
            val response = webClient.get(node.port, node.host, "/admin/system")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                val sysInfo = response.bodyAsJson(SystemInfo::class.java)
                logService.log("info", "Successfully retrieved system info", mapOf("nodeName" to node.name))
                Future.succeededFuture(sysInfo)
            } else {
                logService.log("warn", "Failed to retrieve system info", mapOf("nodeName" to node.name, "statusCode" to response.statusCode()))
                Future.failedFuture("System info retrieval failed: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "System info retrieval failed for node: ${node.name}" }
            logService.logError("Exception retrieving system info", e, mapOf("nodeName" to node.name))
            Future.failedFuture(e)
        }
    }

    /**
     * Resets statistics for a specific node
     *
     * @param nodeName Name of the node
     * @return Future indicating success or failure
     */
    suspend fun resetNodeStats(nodeName: String): Future<Void> {
        val node = nodes.find { it.name == nodeName }
            ?: return Future.failedFuture("Node $nodeName not found")

        logger.info { "Resetting stats for node: ${node.name}" }
        logService.log("info", "Resetting node stats", mapOf("nodeName" to node.name))

        return try {
            val response = webClient.post(node.port, node.host, "/admin/reset-stats")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                logService.log("info", "Successfully reset node stats", mapOf("nodeName" to node.name))
                Future.succeededFuture()
            } else {
                logService.log("warn", "Failed to reset stats", mapOf("nodeName" to node.name, "statusCode" to response.statusCode()))
                Future.failedFuture("Reset stats failed: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Reset stats failed for node: ${node.name}" }
            logService.logError("Exception resetting node stats", e, mapOf("nodeName" to node.name))
            Future.failedFuture(e)
        }
    }

    /**
     * Gets logs for a specific node
     *
     * @param nodeName Name of the node
     * @param level Log level filter
     * @return Future with logs
     */
    suspend fun getNodeLogs(nodeName: String, level: String? = null): Future<List<Map<String, Any>>> {
        val node = nodes.find { it.name == nodeName }
            ?: return Future.failedFuture("Node $nodeName not found")

        logger.info { "Getting logs for node: ${node.name}, level: $level" }

        return try {
            val request = webClient.get(node.port, node.host, "/admin/logs")

            // Add level parameter if provided
            if (level != null) {
                request.addQueryParam("level", level)
            }

            val response = request
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                val logs = response.bodyAsJsonArray().map { it as JsonObject }
                    .map { json -> json.map }
                Future.succeededFuture(logs)
            } else {
                Future.failedFuture("Failed to get logs with status code: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get logs for node: ${node.name}" }
            Future.failedFuture(e)
        }
    }

    /**
     * Gets the best node for a specific model based on node capabilities and load
     *
     * @param modelId The ID of the model
     * @return The name of the best node, or null if no suitable node is found
     */
    suspend fun getBestNodeForModel(modelId: String): String? {
        logService.log("info", "Finding best node for model", mapOf(
            "modelId" to modelId
        ))

        // Get nodes that have this model
        val nodesWithModel = mutableListOf<Node>()
        val nodeStatuses = getAllNodesStatus().coAwait()

        for (status in nodeStatuses) {
            if (status.status != "online") continue

            try {
                val models = getNodeModels(status.node.name).coAwait()
                if (models.any { it.id == modelId }) {
                    nodesWithModel.add(status.node)
                }
            } catch (e: Exception) {
                logService.log("warn", "Failed to check models on node", mapOf(
                    "nodeName" to status.node.name,
                    "error" to e.message.orEmpty()
                ))
            }
        }

        if (nodesWithModel.isEmpty()) {
            logService.log("warn", "No nodes found with model", mapOf(
                "modelId" to modelId
            ))
            return null
        }

        // First, check if there are GPU nodes available for this model
        val gpuNodes = nodesWithModel.filter { it.type == "gpu" }
        if (gpuNodes.isNotEmpty()) {
            val selectedNode = gpuNodes[0]
            logService.log("info", "Selected GPU node for model", mapOf(
                "modelId" to modelId,
                "nodeName" to selectedNode.name
            ))
            return selectedNode.name
        }

        val selectedNode = nodesWithModel[0]
        logService.log("info", "Selected CPU node for model", mapOf(
            "modelId" to modelId,
            "nodeName" to selectedNode.name
        ))
        return selectedNode.name
    }


    /**
     * Gets node load information for all nodes
     *
     * @return Map of node name to current load
     */
    suspend fun getNodeLoads(): Map<String, Double> {
        val nodeLoads = mutableMapOf<String, Double>()
        val nodeStatuses = getAllNodesStatus().coAwait()

        for (status in nodeStatuses) {
            if (status.status != "online") continue

            try {
                // Get queue status for the node
                val response = webClient.get(status.node.port, status.node.host, "/queue-status")
                    .timeout(5000)
                    .send()
                    .coAwait()

                if (response.statusCode() == 200) {
                    val queueInfo = response.bodyAsJsonObject()
                    val queueSize = queueInfo.getInteger("size", 0)
                    val queuePending = queueInfo.getInteger("pending", 0)

                    // Calculate load as a combination of queue size and pending tasks
                    // We'll use a simple formula: load = pending + (size / 2)
                    // This prioritizes nodes that are currently processing fewer requests
                    val load = queuePending + (queueSize / 2.0)
                    nodeLoads[status.node.name] = load
                }
            } catch (e: Exception) {
                logger.warn { "Failed to get queue status for node ${status.node.name}: ${e.message}" }
                // If we can't get queue status, assume high load
                nodeLoads[status.node.name] = Double.MAX_VALUE
            }
        }

        return nodeLoads
    }
}
