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
    logService: LogService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    /**
     * Gets all models available in the cluster
     */
    fun getAllModels(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "All models in cluster requested (requestId: $requestId)" }

        launch {
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
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Checks availability of a specific model
     */
    fun checkModelAvailability(ctx: RoutingContext) {
        val modelId = ctx.pathParam("modelId")
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Model availability requested: $modelId (requestId: $requestId)" }

        launch {
            try {
                val availability = clusterService.checkModelAvailability(modelId)
                val response = ApiResponse.success(availability)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to check model availability: $modelId (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Get overall cluster status
     */
    fun getClusterStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cluster status requested (requestId: $requestId)" }

        launch {
            try {
                val status = clusterService.getClusterStatus()
                val response = ApiResponse.success(status)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cluster status (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Get cluster-wide metrics
     */
    fun getClusterMetrics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cluster metrics requested (requestId: $requestId)" }

        launch {
            try {
                val metrics = clusterService.getClusterMetrics()
                val response = ApiResponse.success(metrics)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cluster metrics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Reset statistics for all nodes
     */
    fun resetClusterStats(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Reset cluster stats requested (requestId: $requestId)" }

        launch {
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
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Get logs from all nodes
     */
    fun getClusterLogs(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val level = ctx.request().getParam("level")
        logger.info { "Cluster logs requested, level: $level (requestId: $requestId)" }

        launch {
            try {
                val logs = clusterService.getClusterLogs(level)
                val response = ApiResponse.success(logs)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cluster logs (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
