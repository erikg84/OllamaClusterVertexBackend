package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.NodeStatus
import com.dallaslabs.services.NodeService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Handler for node-related requests
 */
class NodeHandler(
    private val vertx: Vertx,
    private val nodeService: NodeService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    /**
     * Lists all nodes
     */
    fun listNodes(ctx: RoutingContext) {
        logger.info { "List nodes requested" }

        val nodes = nodeService.getNodes()
        val response = ApiResponse.success(nodes)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }

    /**
     * Gets the models available on a node
     */
    fun getNodeModels(ctx: RoutingContext) {
        val nodeName = ctx.pathParam("name")
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Node models requested: $nodeName (requestId: $requestId)" }

        launch {
            try {
                val models = nodeService.getNodeModels(nodeName).coAwait()
                val response = ApiResponse.success(models)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get models for node $nodeName (requestId: $requestId)" }

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets the status of a specific node
     */
    fun getNodeStatus(ctx: RoutingContext) {
        val nodeName = ctx.pathParam("name")
        logger.info { "Node status requested: $nodeName" }

        launch {
            try {
                val status = nodeService.getNodeStatus(nodeName).coAwait()
                val response = ApiResponse.success<NodeStatus>(status)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get node status for $nodeName" }

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets the status of all nodes
     */
    fun getAllNodesStatus(ctx: RoutingContext) {
        logger.info { "All nodes status requested" }

        launch {
            try {
                val statuses = nodeService.getAllNodesStatus().coAwait()
                val response = ApiResponse.success(statuses)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get all nodes status" }

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
