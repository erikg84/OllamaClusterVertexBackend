package com.dallaslabs.handlers

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging
import java.util.UUID

/**
 * Adds a unique request ID to each request
 */
class RequestIdHandler private constructor() : Handler<RoutingContext> {

    override fun handle(ctx: RoutingContext) {
        // Check if request ID is already set
        var requestId = ctx.request().getHeader("X-Request-ID")
        if (requestId == null) {
            requestId = UUID.randomUUID().toString()
        }

        // Store in context for later use
        ctx.put("requestId", requestId)

        // Set header in response
        ctx.response().putHeader("X-Request-ID", requestId)

        ctx.next()
    }

    companion object {
        fun create(): RequestIdHandler {
            return RequestIdHandler()
        }
    }
}

/**
 * Logs request and response details and publishes metrics
 */
class LoggingHandler private constructor(private val vertx: Vertx) : Handler<RoutingContext> {

    private val logger = KotlinLogging.logger {}

    override fun handle(ctx: RoutingContext) {
        val start = System.currentTimeMillis()
        val path = ctx.request().path()
        val method = ctx.request().method().name()
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        // Process the request
        ctx.addEndHandler { res ->
            val end = System.currentTimeMillis()
            val latency = end - start
            val status = ctx.response().statusCode
            val success = status < 400

            logger.info {
                "Request processed: method=$method path=$path status=$status " +
                        "latency=${latency}ms requestId=$requestId clientIp=${ctx.request().remoteAddress().host()}"
            }

            // Publish metrics
            vertx.eventBus().publish("request.completed", JsonObject()
                .put("requestId", requestId)
                .put("path", path)
                .put("method", method)
                .put("status", status)
                .put("latency", latency)
                .put("success", success)
            )
        }

        ctx.next()
    }

    companion object {
        fun create(vertx: Vertx): LoggingHandler {
            return LoggingHandler(vertx)
        }
    }
}
