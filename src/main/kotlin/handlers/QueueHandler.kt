package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.QueueStatus
import com.dallaslabs.services.LogService
import com.dallaslabs.tracking.FlowTracker
import com.dallaslabs.utils.Queue
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for queue-related requests
 */
class QueueHandler(private val queue: Queue, private val logService: LogService) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    fun getStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue status requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/queue/status",
            "method" to "GET",
            "type" to "queue_status"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        launch {
            logService.log("info", "Queue status requested", mapOf("requestId" to requestId))
        }

        val startTime = System.currentTimeMillis()
        val status = QueueStatus(
            size = queue.size(),
            pending = queue.activeCount(),
            isPaused = queue.isPaused()
        )
        val processingTime = System.currentTimeMillis() - startTime

        // Record queue metrics
        FlowTracker.recordMetrics(requestId, mapOf(
            "queueSize" to status.size,
            "activeTasks" to status.pending,
            "isPaused" to status.isPaused,
            "processingTimeMs" to processingTime
        ))

        val response = ApiResponse.success(status)

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

    fun pauseQueue(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue pause requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/queue/pause",
            "method" to "POST",
            "type" to "queue_operation",
            "operation" to "pause"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        // Record pre-operation metrics
        FlowTracker.recordMetrics(requestId, mapOf(
            "preOperationQueueSize" to queue.size(),
            "preOperationActiveTasks" to queue.activeCount(),
            "preOperationIsPaused" to queue.isPaused()
        ))

        val startTime = System.currentTimeMillis()
        try {
            queue.pause()

            launch {
                logService.log("info", "Queue paused", mapOf("requestId" to requestId))
            }

            val processingTime = System.currentTimeMillis() - startTime

            // Record post-operation metrics
            FlowTracker.recordMetrics(requestId, mapOf(
                "postOperationQueueSize" to queue.size(),
                "postOperationActiveTasks" to queue.activeCount(),
                "postOperationIsPaused" to queue.isPaused(),
                "processingTimeMs" to processingTime
            ))

            val response = ApiResponse.success<Unit>(message = "Queue paused successfully")

            // Update state to COMPLETED
            FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                "responseTime" to processingTime,
                "statusCode" to 200,
                "success" to true
            ))

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(JsonObject.mapFrom(response).encode())

        } catch (e: Exception) {
            logger.error(e) { "Failed to pause queue (requestId: $requestId)" }

            launch {
                logService.logError("Failed to pause queue", e, mapOf("requestId" to requestId))
            }

            // Record error and transition to FAILED state
            FlowTracker.recordError(requestId, "queue_pause_error", e.message ?: "Unknown error")

            val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(500)
                .end(JsonObject.mapFrom(response).encode())
        }
    }

    fun resumeQueue(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue resume requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/queue/resume",
            "method" to "POST",
            "type" to "queue_operation",
            "operation" to "resume"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        // Record pre-operation metrics
        FlowTracker.recordMetrics(requestId, mapOf(
            "preOperationQueueSize" to queue.size(),
            "preOperationActiveTasks" to queue.activeCount(),
            "preOperationIsPaused" to queue.isPaused()
        ))

        val startTime = System.currentTimeMillis()
        try {
            queue.resume()

            launch {
                logService.log("info", "Queue resumed", mapOf("requestId" to requestId))
            }

            val processingTime = System.currentTimeMillis() - startTime

            // Record post-operation metrics
            FlowTracker.recordMetrics(requestId, mapOf(
                "postOperationQueueSize" to queue.size(),
                "postOperationActiveTasks" to queue.activeCount(),
                "postOperationIsPaused" to queue.isPaused(),
                "processingTimeMs" to processingTime
            ))

            val response = ApiResponse.success<Unit>(message = "Queue resumed successfully")

            // Update state to COMPLETED
            FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                "responseTime" to processingTime,
                "statusCode" to 200,
                "success" to true
            ))

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(JsonObject.mapFrom(response).encode())

        } catch (e: Exception) {
            logger.error(e) { "Failed to resume queue (requestId: $requestId)" }

            launch {
                logService.logError("Failed to resume queue", e, mapOf("requestId" to requestId))
            }

            // Record error and transition to FAILED state
            FlowTracker.recordError(requestId, "queue_resume_error", e.message ?: "Unknown error")

            val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(500)
                .end(JsonObject.mapFrom(response).encode())
        }
    }
}