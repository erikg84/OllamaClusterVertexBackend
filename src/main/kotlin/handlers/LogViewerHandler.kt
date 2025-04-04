package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.services.NodeService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Handler for log viewing operations
 */
class LogViewerHandler(
    private val vertx: Vertx,
    private val logService: LogService,
    private val nodeService: NodeService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    /**
     * Gets available server names
     */
    fun getServers(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Available servers requested (requestId: $requestId)" }

        launch {
            try {
                val servers = logService.getAvailableServers()

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonArray(servers).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get available servers (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets available log levels
     */
    fun getLevels(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Available log levels requested (requestId: $requestId)" }

        launch {
            try {
                val levels = logService.getAvailableLevels()

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonArray(levels).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get available log levels (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets logs with pagination and filtering
     */
    fun getLogs(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        // Extract query parameters
        val page = ctx.request().getParam("page")?.toIntOrNull() ?: 1
        val limit = ctx.request().getParam("limit")?.toIntOrNull() ?: 50
        val serverId = ctx.request().getParam("serverId")
        val level = ctx.request().getParam("level")
        val message = ctx.request().getParam("message")
        val requestIdFilter = ctx.request().getParam("requestId")
        val startDate = ctx.request().getParam("startDate")
        val endDate = ctx.request().getParam("endDate")

        logger.info {
            "Logs requested with filters: page=$page, limit=$limit, serverId=$serverId, " +
                    "level=$level, message=$message, requestId=$requestIdFilter, " +
                    "startDate=$startDate, endDate=$endDate (requestId: $requestId)"
        }

        launch {
            try {
                val result = logService.getLogs(
                    page = page,
                    limit = limit,
                    serverId = serverId,
                    level = level,
                    message = message,
                    requestId = requestIdFilter,
                    startDate = startDate,
                    endDate = endDate,
                    nodeService = nodeService
                )

                val logsArray = JsonArray()
                result.logs.forEach { log ->
                    logsArray.add(log.toJson(nodeService))
                }

                val response = JsonObject()
                    .put("logs", logsArray)
                    .put("pagination", JsonObject()
                        .put("page", page)
                        .put("limit", limit)
                        .put("total", result.total)
                        .put("pages", (result.total + limit - 1) / limit)
                    )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(response.encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get logs (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets log statistics
     */
    fun getStats(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Log statistics requested (requestId: $requestId)" }

        launch {
            try {
                val stats = logService.getLogStats()

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(stats).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get log statistics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets node names for the server filter
     */
    fun getNodes(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Available nodes requested (requestId: $requestId)" }

        launch {
            try {
                // Get the list of nodes using the injected nodeService
                val nodes = nodeService.getNodes()

                // Convert to array of names for the dropdown
                val nodeNames = JsonArray(nodes.map { it.name })

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(nodeNames.encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get available nodes (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets full node information
     */
    fun getNodeDetails(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Node details requested (requestId: $requestId)" }

        launch {
            try {
                // Get the list of nodes
                val nodes = nodeService.getNodes()
                val response = ApiResponse.success(nodes)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get node details (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
