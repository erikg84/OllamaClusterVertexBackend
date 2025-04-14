package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.services.PerformanceOptimizationService
import com.dallaslabs.tracking.FlowTracker
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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/performance/prewarm",
            "method" to "POST",
            "type" to "model_prewarm"
        ))

        launch {
            logService.log("info", "Model pre-warming requested", mapOf("requestId" to requestId))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                    "analysisStartedAt" to System.currentTimeMillis()
                ))

                val body = ctx.body().asJsonObject()
                val model = body.getString("model")
                val nodeName = body.getString("node", null)

                // Record prewarm parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "model" to model,
                    "specificNode" to (nodeName != null),
                    "targetNode" to (nodeName ?: "auto-select")
                ))

                if (model.isBlank()) {
                    FlowTracker.recordError(requestId, "validation_error", "Model is required")
                    respondWithError(ctx, 400, "Model is required")
                    return@launch
                }

                // Update state to MODEL_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                    "model" to model
                ))

                if (nodeName != null) {
                    // Update state to NODE_SELECTION if specific node
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
                        "selectedNode" to nodeName
                    ))
                }

                // Update state to EXECUTING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                    "operation" to "prewarm",
                    "executionStartedAt" to System.currentTimeMillis()
                ))

                val startTime = System.currentTimeMillis()
                performanceOptimizationService.preWarmModel(model, nodeName)
                val processingTime = System.currentTimeMillis() - startTime

                // Record processing time
                FlowTracker.recordMetrics(requestId, mapOf(
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success<Unit>(
                    message = "Model pre-warming initiated successfully"
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to pre-warm model (requestId: $requestId)" }
                logService.logError("Failed to pre-warm model", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "prewarm_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/performance/parameters/$model/$taskType",
            "method" to "GET",
            "type" to "optimized_parameters",
            "model" to model,
            "taskType" to taskType
        ))

        launch {
            logService.log("info", "Optimized parameters requested", mapOf(
                "requestId" to requestId,
                "model" to model,
                "taskType" to taskType
            ))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                // Update state to MODEL_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                    "model" to model,
                    "taskType" to taskType
                ))

                val startTime = System.currentTimeMillis()
                val parameters = performanceOptimizationService.getOptimizedParameters(model, taskType)
                val processingTime = System.currentTimeMillis() - startTime

                // Record parameters retrieved
                FlowTracker.recordMetrics(requestId, mapOf(
                    "parameterCount" to parameters.size(),
                    "parameters" to parameters.map.keys.joinToString(","),
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(
                    data = parameters.map
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

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

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "parameter_retrieval_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/performance/cache/stats",
            "method" to "GET",
            "type" to "cache_stats"
        ))

        launch {
            logService.log("info", "Cache statistics requested", mapOf("requestId" to requestId))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val startTime = System.currentTimeMillis()

                // For demonstration, using placeholder statistics
                // In a real implementation, this would come from the service
                val stats = JsonObject()
                    .put("entries", 250)
                    .put("hitRate", 0.76)
                    .put("missRate", 0.24)
                    .put("avgResponseSize", 4500)

                val processingTime = System.currentTimeMillis() - startTime

                // Record cache statistics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "cacheEntries" to stats.getInteger("entries"),
                    "cacheHitRate" to stats.getDouble("hitRate"),
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(
                    data = stats.map
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get cache statistics (requestId: $requestId)" }
                logService.logError("Failed to get cache statistics", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "cache_stats_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/performance/batch/stats",
            "method" to "GET",
            "type" to "batch_queue_stats"
        ))

        launch {
            logService.log("info", "Batch queue statistics requested", mapOf("requestId" to requestId))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val startTime = System.currentTimeMillis()

                // For demonstration, using placeholder statistics
                // In a real implementation, this would come from the service
                val stats = JsonObject()
                    .put("activeQueues", 3)
                    .put("totalQueuedRequests", 12)
                    .put("avgProcessingTimeMs", 180)
                    .put("avgBatchSize", 4.3)

                val processingTime = System.currentTimeMillis() - startTime

                // Record batch queue statistics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "activeQueues" to stats.getInteger("activeQueues"),
                    "queuedRequests" to stats.getInteger("totalQueuedRequests"),
                    "avgBatchSize" to stats.getDouble("avgBatchSize"),
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(
                    data = stats.map
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get batch queue statistics (requestId: $requestId)" }
                logService.logError("Failed to get batch queue statistics", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "batch_queue_stats_error", e.message ?: "Unknown error")

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