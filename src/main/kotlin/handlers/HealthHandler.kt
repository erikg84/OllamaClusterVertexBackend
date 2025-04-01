package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for health check requests
 */
class HealthHandler {

    /**
     * Handles health check requests
     */
    fun handle(ctx: RoutingContext) {
        logger.info { "Health check requested" }

        val response = ApiResponse.success<Unit>(
            message = "Service is healthy"
        )

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(JsonObject.mapFrom(response).encode())
    }
}
