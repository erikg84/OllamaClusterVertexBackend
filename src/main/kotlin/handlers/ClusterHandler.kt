package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.ClusterService
import com.dallaslabs.services.LogService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Handler for cluster-related operations
 */
class ClusterHandler(
    private val vertx: Vertx,
    private val clusterService: ClusterService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    fun getAllModels(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "All models in cluster requested (requestId: $requestId)" }

        launch {
            logService.log("info", "All models in cluster requested", mapOf("requestId" to requestId))
            try {
                val models = clusterService.getAllModels().map { it.copy(status = "loaded") }
                val response = ApiResponse.success(
                    data = models,
                    message = "Cluster Models: ${models.size} found"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get all models (requestId: $requestId)" }
                logService.logError("Failed to get all models", e, mapOf("requestId" to requestId))
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun checkModelAvailability(ctx: RoutingContext) {
        val modelId = ctx.pathParam("modelId")
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Model availability requested: $modelId (requestId: $requestId)" }

        launch {
            logService.log("info", "Model availability requested", mapOf("modelId" to modelId, "requestId" to requestId))
            try {
                val availability = clusterService.checkModelAvailability(modelId)
                val response = ApiResponse.success(availability)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to check model availability: $modelId (requestId: $requestId)" }
                logService.logError("Failed to check model availability", e, mapOf("modelId" to modelId, "requestId" to requestId))
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getClusterStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cluster status requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Cluster status requested", mapOf("requestId" to requestId))
            try {
                val status = clusterService.getClusterStatus()
                val response = ApiResponse.success(status)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cluster status (requestId: $requestId)" }
                logService.logError("Failed to get cluster status", e, mapOf("requestId" to requestId))
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getClusterMetrics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cluster metrics requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Cluster metrics requested", mapOf("requestId" to requestId))
            try {
                val metrics = clusterService.getClusterMetrics()
                val response = ApiResponse.success(metrics)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cluster metrics (requestId: $requestId)" }
                logService.logError("Failed to get cluster metrics", e, mapOf("requestId" to requestId))
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun resetClusterStats(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Reset cluster stats requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Reset cluster stats requested", mapOf("requestId" to requestId))
            try {
                val errors = clusterService.resetClusterStats()

                if (errors.isEmpty()) {
                    val response = ApiResponse.success<Unit>(message = "All nodes reset successfully")

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(JsonObject.mapFrom(response).encode())
                } else {
                    val errorMsg = "Some nodes failed to reset: ${errors.joinToString(", ")}"
                    val response = ApiResponse.error<Nothing>(errorMsg)

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(500)
                        .end(JsonObject.mapFrom(response).encode())
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to reset cluster stats (requestId: $requestId)" }
                logService.logError("Failed to reset cluster stats", e, mapOf("requestId" to requestId))
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getClusterLogs(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val level = ctx.request().getParam("level")
        logger.info { "Cluster logs requested, level: $level (requestId: $requestId)" }

        launch {
            logService.log("info", "Cluster logs requested", mapOf("requestId" to requestId, "level" to (level ?: "none")))
            try {
                val logs = clusterService.getClusterLogs(level)
                val response = ApiResponse.success(logs)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cluster logs (requestId: $requestId)" }
                logService.logError("Failed to get cluster logs", e, mapOf("requestId" to requestId))
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
