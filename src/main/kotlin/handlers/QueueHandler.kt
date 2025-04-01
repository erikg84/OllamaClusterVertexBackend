package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.QueueStatus
import com.dallaslabs.utils.Queue
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for queue-related requests
 */
class QueueHandler(private val queue: Queue) {

    /**
     * Gets the current status of the queue
     */
    fun getStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue status requested (requestId: $requestId)" }

        val status = QueueStatus(
            size = queue.size(),
            pending = queue.activeCount(),
            isPaused = queue.isPaused()  // New method
        )

        val response = ApiResponse.success(status)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }

    /**
     * Pauses the queue
     */
    fun pauseQueue(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue pause requested (requestId: $requestId)" }

        queue.pause()

        val response = ApiResponse.success<Unit>(message = "Queue paused successfully")

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }

    /**
     * Resumes the queue
     */
    fun resumeQueue(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue resume requested (requestId: $requestId)" }

        queue.resume()

        val response = ApiResponse.success<Unit>(message = "Queue resumed successfully")

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }
}
