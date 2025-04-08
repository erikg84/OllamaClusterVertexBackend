package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
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
        logger.info { "Health check requested" }

        launch {
            logService.log("info", "Health check request handled", mapOf(
                "path" to ctx.request().path(),
                "remoteAddress" to ctx.request().remoteAddress().host()
            ))
        }

        val response = ApiResponse.success<Unit>(
            message = "Service is healthy"
        )

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }
}