package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.services.PerformanceOptimizationService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for performance optimization operations
 */
class PerformanceOptimizationHandler(
    private val vertx: Vertx,
    private val performanceOptimizationService: PerformanceOptimizationService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    /**
     * Pre-warms a model
     */
    fun preWarmModel(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Model pre-warming requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Model pre-warming requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val model = body.getString("model")
                val nodeName = body.getString("node", null)

                if (model.isBlank()) {
                    respondWithError(ctx, 400, "Model is required")
                    return@launch
                }

                performanceOptimizationService.preWarmModel(model, nodeName)

                val response = ApiResponse.success<Unit>(
                    message = "Model pre-warming initiated successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to pre-warm model (requestId: $requestId)" }
                logService.logError("Failed to pre-warm model", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Gets optimized parameters for a model and task type
     */
    fun getOptimizedParameters(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val model = ctx.pathParam("model")
        val taskType = ctx.pathParam("taskType")

        logger.info { "Optimized parameters requested: $model/$taskType (requestId: $requestId)" }

        launch {
            logService.log("info", "Optimized parameters requested", mapOf(
                "requestId" to requestId,
                "model" to model,
                "taskType" to taskType
            ))
            try {
                val parameters = performanceOptimizationService.getOptimizedParameters(model, taskType)

                val response = ApiResponse.success(
                    data = parameters.map
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get optimized parameters: $model/$taskType (requestId: $requestId)" }
                logService.logError("Failed to get optimized parameters", e, mapOf(
                    "requestId" to requestId,
                    "model" to model,
                    "taskType" to taskType
                ))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Gets cache statistics
     */
    fun getCacheStats(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cache statistics requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Cache statistics requested", mapOf("requestId" to requestId))
            try {
                // For demonstration, using placeholder statistics
                // In a real implementation, this would come from the service
                val stats = JsonObject()
                    .put("entries", 250)
                    .put("hitRate", 0.76)
                    .put("missRate", 0.24)
                    .put("avgResponseSize", 4500)

                val response = ApiResponse.success(
                    data = stats.map
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cache statistics (requestId: $requestId)" }
                logService.logError("Failed to get cache statistics", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Gets batch queue statistics
     */
    fun getBatchQueueStats(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Batch queue statistics requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Batch queue statistics requested", mapOf("requestId" to requestId))
            try {
                // For demonstration, using placeholder statistics
                // In a real implementation, this would come from the service
                val stats = JsonObject()
                    .put("activeQueues", 3)
                    .put("totalQueuedRequests", 12)
                    .put("avgProcessingTimeMs", 180)
                    .put("avgBatchSize", 4.3)

                val response = ApiResponse.success(
                    data = stats.map
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get batch queue statistics (requestId: $requestId)" }
                logService.logError("Failed to get batch queue statistics", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    private fun respondWithError(ctx: RoutingContext, statusCode: Int, message: String) {
        val response = ApiResponse.error<Nothing>(message)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(statusCode)
            .end(JsonObject.mapFrom(response).encode())
    }
}