package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.tracking.FlowTracker
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for health check requests
 */
class HealthHandler(private val logService: LogService) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    /**
     * Handles health check requests
     */
    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Health check requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/health",
            "method" to "GET",
            "type" to "health_check"
        ))

        // Update state to ANALYZING - for health checks, this is a simple verification
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        val startTime = System.currentTimeMillis()

        // In a more complex implementation, we might check database connectivity,
        // verify downstream services, etc. before responding with healthy status

        launch {
            logService.log("info", "Health check request handled", mapOf(
                "path" to ctx.request().path(),
                "remoteAddress" to ctx.request().remoteAddress().host(),
                "requestId" to requestId
            ))
        }

        val processingTime = System.currentTimeMillis() - startTime

        // Record health check metrics
        FlowTracker.recordMetrics(requestId, mapOf(
            "processingTimeMs" to processingTime,
            "healthy" to true,
            "checkType" to "basic" // Could be "comprehensive" for deeper health checks
        ))

        val response = ApiResponse.success<Unit>(
            message = "Service is healthy"
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
    }
}