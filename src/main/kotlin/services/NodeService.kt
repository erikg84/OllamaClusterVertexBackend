package com.dallaslabs.services

import com.dallaslabs.models.ModelInfo
import com.dallaslabs.models.Node
import com.dallaslabs.models.NodeStatus
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
class NodeService(vertx: Vertx, private val nodes: List<Node>) {

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
}
