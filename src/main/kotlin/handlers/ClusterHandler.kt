package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.ClusterService
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
    private val clusterService: ClusterService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    /**
     * Gets overall cluster status
     */
    fun getStatus(ctx: RoutingContext) {
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
     * Gets all models available in the cluster
     */
    fun getAllModels(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "All models in cluster requested (requestId: $requestId)" }

        launch {
            try {
                val models = clusterService.getAllModels()
                val response = ApiResponse.success(models)

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
}
