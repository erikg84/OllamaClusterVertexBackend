package com.dallaslabs.services

import com.dallaslabs.models.*
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Service for managing LLM nodes
 */
class NodeService(vertx: Vertx, private val nodes: List<Node>, logService: LogService) {

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
                    Future.succeededFuture(models)
                } catch (e: Exception) {
                    // If that fails, try to parse as an object with a models field
                    try {
                        val jsonObject = response.bodyAsJsonObject()
                        if (jsonObject.containsKey("models")) {
                            val modelsArray = jsonObject.getJsonArray("models")
                            val models = modelsArray.map { json ->
                                val modelJson = json as JsonObject
                                ModelInfo(
                                    id = modelJson.getString("id", "unknown"),
                                    name = modelJson.getString("name", "unknown"),
                                    type = modelJson.getString("type", "unknown"),
                                    size = modelJson.getLong("size", 0),
                                    quantization = modelJson.getString("quantization", "unknown")
                                )
                            }
                            Future.succeededFuture(models)
                        } else {
                            // If there's no models field, return an empty list
                            Future.succeededFuture(emptyList())
                        }
                    } catch (e2: Exception) {
                        // If both parsing attempts fail, log and return the error
                        logger.error(e2) { "Failed to parse models response for node: ${node.name}" }
                        Future.failedFuture("Failed to parse models response: ${e2.message}")
                    }
                }
            } else {
                Future.failedFuture("Failed to get models with status code: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get models for node: ${node.name}" }
            Future.failedFuture(e)
        }
    }

    /**
     * Returns all nodes
     */
    fun getNodes(): List<Node> = nodes

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

        return try {
            val response = webClient.get(node.port, node.host, "/health")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                Future.succeededFuture(
                    NodeStatus(
                        node = node,
                        status = "online"
                    )
                )
            } else {
                Future.succeededFuture(
                    NodeStatus(
                        node = node,
                        status = "error",
                        message = "Health check failed with status code: ${response.statusCode()}"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to check node health: ${node.name}" }
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

        val statuses = mutableListOf<NodeStatus>()

        for (node in nodes) {
            try {
                val status = getNodeStatus(node.name).coAwait()
                statuses.add(status)
            } catch (e: Exception) {
                logger.error(e) { "Failed to get status of node: ${node.name}" }
                // Continue with other nodes
            }
        }

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

        logger.info { "Getting metrics for node: ${node.name}" }

        return try {
            val response = webClient.get(node.port, node.host, "/admin/metrics")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                val metrics = response.bodyAsJson(NodeMetrics::class.java)
                Future.succeededFuture(metrics)
            } else {
                Future.failedFuture("Failed to get metrics with status code: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get metrics for node: ${node.name}" }
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

        return try {
            val response = webClient.get(node.port, node.host, "/admin/system")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                val sysInfo = response.bodyAsJson(SystemInfo::class.java)
                Future.succeededFuture(sysInfo)
            } else {
                Future.failedFuture("Failed to get system info with status code: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get system info for node: ${node.name}" }
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

        return try {
            val response = webClient.post(node.port, node.host, "/admin/reset-stats")
                .timeout(5000)
                .send()
                .coAwait()

            if (response.statusCode() == 200) {
                Future.succeededFuture()
            } else {
                Future.failedFuture("Failed to reset stats with status code: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to reset stats for node: ${node.name}" }
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
        logger.info { "Finding best node for model: $modelId" }

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
                logger.warn { "Failed to check models on node ${status.node.name}: ${e.message}" }
            }
        }

        if (nodesWithModel.isEmpty()) {
            logger.warn { "No nodes found with model: $modelId" }
            return null
        }

        // First, check if there are GPU nodes available for this model
        val gpuNodes = nodesWithModel.filter { it.type == "gpu" }
        if (gpuNodes.isNotEmpty()) {
            // For now, just return the first GPU node
            // This will be enhanced with load balancing later
            return gpuNodes[0].name
        }

        // If no GPU nodes, use a CPU node
        return nodesWithModel[0].name
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
