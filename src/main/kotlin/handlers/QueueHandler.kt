package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.QueueStatus
import com.dallaslabs.services.LogService
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

        launch {
            logService.log("info", "Queue status requested", mapOf("requestId" to requestId))
        }

        val status = QueueStatus(
            size = queue.size(),
            pending = queue.activeCount(),
            isPaused = queue.isPaused()
        )

        val response = ApiResponse.success(status)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }

    fun pauseQueue(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue pause requested (requestId: $requestId)" }

        queue.pause()

        launch {
            logService.log("info", "Queue paused", mapOf("requestId" to requestId))
        }

        val response = ApiResponse.success<Unit>(message = "Queue paused successfully")

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }

    fun resumeQueue(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Queue resume requested (requestId: $requestId)" }

        queue.resume()

        launch {
            logService.log("info", "Queue resumed", mapOf("requestId" to requestId))
        }

        val response = ApiResponse.success<Unit>(message = "Queue resumed successfully")

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }
}
