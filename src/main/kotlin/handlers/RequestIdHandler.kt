package com.dallaslabs.handlers

import com.dallaslabs.tracking.FlowTracker
import com.dallaslabs.services.LogService
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.UUID

/**
 * Adds a unique request ID to each request and initializes request flow tracking
 */
class RequestIdHandler private constructor() : Handler<RoutingContext> {

    private val logger = KotlinLogging.logger {}

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

        // Start tracking the request flow
        FlowTracker.startFlow(
            requestId, mapOf(
                "path" to ctx.request().path(),
                "method" to ctx.request().method().name(),
                "clientIp" to ctx.request().remoteAddress().host(),
                "userAgent" to (ctx.request().getHeader("User-Agent") ?: "unknown"),
                "receivedAt" to System.currentTimeMillis()
            )
        )

        // Update state to RECEIVED
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.RECEIVED, mapOf(
                "requestSize" to ctx.request().bytesRead()
            )
        )

        logger.debug { "Request tracking initiated: $requestId" }

        ctx.next()
    }

    companion object {
        fun create(): RequestIdHandler {
            return RequestIdHandler()
        }
    }
}

/**
 * Logs request and response details, publishes metrics, and completes request flow tracking
 * Now enhanced with MongoDB logging integration
 */
class LoggingHandler private constructor(
    private val vertx: Vertx,
    private val logService: LogService
) : Handler<RoutingContext>, CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val logger = KotlinLogging.logger {}

    override fun handle(ctx: RoutingContext) {
        val start = System.currentTimeMillis()
        val path = ctx.request().path()
        val method = ctx.request().method().name()
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        val body = try {
            if (ctx.body().length() > 0) ctx.bodyAsJson else null
        } catch (e: Exception) {
            null
        }

        logService.logRequest(
            requestId = requestId,
            method = method,
            url = ctx.request().absoluteURI(),
            path = path,
            query = ctx.queryParams().entries().associate { it.key to it.value },
            ip = ctx.request().remoteAddress().hostAddress(),
            userAgent = ctx.request().getHeader("user-agent"),
            contentType = ctx.request().getHeader("content-type"),
            body = body
        )

        // Process the request
        ctx.addEndHandler { res ->
            val end = System.currentTimeMillis()
            val latency = end - start
            val status = ctx.response().statusCode
            val success = status < 400
            val responseSize = ctx.response().bytesWritten()

            logger.info {
                "Request processed: method=$method path=$path status=$status " +
                        "latency=${latency}ms requestId=$requestId clientIp=${ctx.request().remoteAddress().host()}"
            }

            // Publish metrics
            vertx.eventBus().publish(
                "request.completed", JsonObject()
                    .put("requestId", requestId)
                    .put("path", path)
                    .put("method", method)
                    .put("status", status)
                    .put("latency", latency)
                    .put("success", success)
            )

            // Record final metrics and complete flow tracking
            FlowTracker.recordMetrics(
                requestId, mapOf(
                    "totalLatencyMs" to latency,
                    "statusCode" to status,
                    "responseSize" to responseSize,
                    "success" to success
                )
            )

            // Update flow state based on response status
            if (success) {
                // If not explicitly completed by a handler, mark as COMPLETED
                if (FlowTracker.getFlowInfo(requestId)?.currentState != FlowTracker.FlowState.COMPLETED) {
                    FlowTracker.updateState(
                        requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                            "responseTime" to latency,
                            "statusCode" to status,
                            "completedAt" to System.currentTimeMillis()
                        )
                    )
                }
            } else {
                // If not explicitly failed by a handler, mark as FAILED
                if (FlowTracker.getFlowInfo(requestId)?.currentState != FlowTracker.FlowState.FAILED) {
                    FlowTracker.recordError(requestId, "http_error", "Request failed with status $status")
                }
            }

            // Log response to MongoDB if LogService is available
            logService.logResponse(
                requestId = requestId,
                method = method,
                url = ctx.request().absoluteURI(),
                statusCode = status,
                duration = latency,
                contentType = ctx.response().headers().get("content-type")
            )

            logger.debug { "Request tracking completed: $requestId (${latency}ms)" }

            // If there was an error and LogService is available, log the error to MongoDB
            if (!success) {
                vertx.executeBlocking<Void>({ promise ->
                    try {
                        val errorMetadata = mapOf(
                            "requestId" to requestId,
                            "method" to method,
                            "path" to path,
                            "statusCode" to status,
                            "latency" to latency,
                            "responseSize" to responseSize
                        )

                        launch {
                            logService.logError(
                                message = "Request failed with status $status",
                                context = errorMetadata
                            )
                        }
                        promise.complete()
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to log error to MongoDB" }
                        promise.fail(e)
                    }
                }, {})
            }
        }

        ctx.next()
    }

    companion object {
        fun create(vertx: Vertx, logService: LogService): LoggingHandler {
            return LoggingHandler(vertx, logService)
        }
    }
}
