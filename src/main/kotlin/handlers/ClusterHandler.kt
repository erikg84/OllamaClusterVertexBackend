package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.ClusterService
import com.dallaslabs.services.LogService
import com.dallaslabs.tracking.FlowTracker
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.UUID
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Handler for cluster-related operations with FlowTracker integration
 */
class ClusterHandler(
    private val vertx: Vertx,
    private val clusterService: ClusterService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    /**
     * Generic method to handle cluster requests with FlowTracker integration
     */
    private suspend fun <T> handleClusterRequest(
        ctx: RoutingContext,
        requestName: String,
        flowState: FlowTracker.FlowState,
        additionalMetadata: Map<String, Any> = emptyMap(),
        requestAction: suspend () -> T
    ) {
        // Generate or retrieve request ID
        val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()

        // Start flow tracking
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to requestName,
            "method" to ctx.request().method().name()
        ) + additionalMetadata)

        logger.info { "$requestName requested (requestId: $requestId)" }

        try {
            // Update flow state to executing
            FlowTracker.updateState(requestId, flowState)

            // Log request
            logService.log("info", "$requestName requested", mapOf("requestId" to requestId))

            // Execute the request
            val result = requestAction()

            // Update flow state to completed
            FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                "responseType" to "success"
            ))

            // Create successful response
            val response = when (result) {
                is Unit -> ApiResponse.success<Unit>(message = "Operation successful")
                else -> ApiResponse.success(result)
            }

            // Send response
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(JsonObject.mapFrom(response).encode())

        } catch (e: Exception) {
            // Record error in flow tracking
            FlowTracker.recordError(
                requestId,
                "ClusterRequestError",
                e.message ?: "Unknown error",
                failFlow = true
            )

            // Log error
            logger.error(e) { "Failed to process $requestName (requestId: $requestId)" }
            logService.logError("Failed to process $requestName", e, mapOf("requestId" to requestId))

            // Create error response
            val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

            // Send error response
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(500)
                .end(JsonObject.mapFrom(response).encode())
        }
    }

    fun getAllModels(ctx: RoutingContext) {
        launch {
            handleClusterRequest(
                ctx,
                "Get All Models",
                FlowTracker.FlowState.ANALYZING
            ) {
                // Map models and add status
                val models = clusterService.getAllModels().map { it.copy(status = "loaded") }
                models
            }
        }
    }

    fun checkModelAvailability(ctx: RoutingContext) {
        val modelId = ctx.pathParam("modelId")

        launch {
            handleClusterRequest(
                ctx,
                "Check Model Availability",
                FlowTracker.FlowState.ANALYZING,
                additionalMetadata = mapOf("modelId" to modelId)
            ) {
                clusterService.checkModelAvailability(modelId)
            }
        }
    }

    fun getClusterStatus(ctx: RoutingContext) {
        launch {
            handleClusterRequest(
                ctx,
                "Get Cluster Status",
                FlowTracker.FlowState.ANALYZING
            ) {
                clusterService.getClusterStatus()
            }
        }
    }

    fun getClusterMetrics(ctx: RoutingContext) {
        launch {
            handleClusterRequest(
                ctx,
                "Get Cluster Metrics",
                FlowTracker.FlowState.ANALYZING
            ) {
                clusterService.getClusterMetrics()
            }
        }
    }

    fun resetClusterStats(ctx: RoutingContext) {
        launch {
            handleClusterRequest(
                ctx,
                "Reset Cluster Stats",
                FlowTracker.FlowState.EXECUTING
            ) {
                val errors = clusterService.resetClusterStats()

                if (errors.isNotEmpty()) {
                    // If there are errors, throw an exception to trigger error handling
                    throw RuntimeException("Some nodes failed to reset: ${errors.joinToString(", ")}")
                }
            }
        }
    }

    fun getClusterLogs(ctx: RoutingContext) {
        val level = ctx.request().getParam("level")

        launch {
            handleClusterRequest(
                ctx,
                "Get Cluster Logs",
                FlowTracker.FlowState.ANALYZING,
                additionalMetadata = mapOf("logLevel" to (level ?: "none"))
            ) {
                clusterService.getClusterLogs(level)
            }
        }
    }
}