package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.NodeStatus
import com.dallaslabs.services.LogService
import com.dallaslabs.services.NodeService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for node-related requests
 */
class NodeHandler(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    fun listNodes(ctx: RoutingContext) {
        logger.info { "List nodes requested" }

        launch {
            logService.log("info", "List nodes requested", mapOf(
                "remoteAddress" to ctx.request().remoteAddress().host()
            ))
        }

        val nodes = nodeService.getNodes()
        val response = ApiResponse.success(nodes)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }

    fun getNodeModels(ctx: RoutingContext) {
        val nodeName = ctx.pathParam("name")
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Node models requested: $nodeName (requestId: $requestId)" }

        launch {
            logService.log("info", "Node models requested", mapOf("nodeName" to nodeName, "requestId" to requestId))

            try {
                val models = nodeService.getNodeModels(nodeName).coAwait()
                val response = ApiResponse.success(models)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get models for node $nodeName (requestId: $requestId)" }

                logService.logError("Failed to get models for node", e, mapOf("nodeName" to nodeName, "requestId" to requestId))

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getNodeStatus(ctx: RoutingContext) {
        val nodeName = ctx.pathParam("name")
        logger.info { "Node status requested: $nodeName" }

        launch {
            logService.log("info", "Node status requested", mapOf("nodeName" to nodeName))

            try {
                val status = nodeService.getNodeStatus(nodeName).coAwait()
                val response = ApiResponse.success<NodeStatus>(status)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get node status for $nodeName" }

                logService.logError("Failed to get node status", e, mapOf("nodeName" to nodeName))

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getAllNodesStatus(ctx: RoutingContext) {
        logger.info { "All nodes status requested" }

        launch {
            logService.log("info", "All nodes status requested", mapOf(
                "remoteAddress" to ctx.request().remoteAddress().host()
            ))

            try {
                val statuses = nodeService.getAllNodesStatus().coAwait()
                val response = ApiResponse.success(statuses)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get all nodes status" }

                logService.logError("Failed to get all nodes status", e)

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
