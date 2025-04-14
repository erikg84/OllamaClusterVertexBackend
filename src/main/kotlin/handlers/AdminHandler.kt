package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.AdminService
import com.dallaslabs.services.LoadBalancerService
import com.dallaslabs.services.LogService
import com.dallaslabs.services.PerformanceTrackerService
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
 * Handler for admin operations with FlowTracker integration
 */
class AdminHandler(
    private val vertx: Vertx,
    private val adminService: AdminService,
    private val loadBalancerService: LoadBalancerService,
    private val performanceTracker: PerformanceTrackerService,
    private val logService: LogService,
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    /**
     * Generic method to handle admin requests with FlowTracker integration
     */
    private suspend fun <T> handleAdminRequest(
        ctx: RoutingContext,
        requestName: String,
        flowState: FlowTracker.FlowState,
        requestAction: suspend () -> T
    ) {
        // Generate or retrieve request ID
        val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()

        // Start flow tracking
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to requestName,
            "method" to ctx.request().method().name()
        ))

        logger.info { "$requestName requested (requestId: $requestId)" }

        try {
            // Update flow state to executing
            FlowTracker.updateState(requestId, flowState)

            // Log request
            logService.log("info", "$requestName requested", mapOf("requestId" to requestId))

            // Execute the request
            val result = requestAction()

            // Create successful response
            val response = ApiResponse.success(result)

            // Update flow state to completed
            FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                "responseType" to "success"
            ))

            // Send response
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(JsonObject.mapFrom(response).encode())

        } catch (e: Exception) {
            // Record error in flow tracking
            FlowTracker.recordError(
                requestId,
                "AdminRequestError",
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

    fun getMetrics(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "Metrics",
                FlowTracker.FlowState.ANALYZING
            ) {
                adminService.getMetrics()
            }
        }
    }

    fun getLoadBalancingMetrics(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "Load Balancing Metrics",
                FlowTracker.FlowState.ANALYZING
            ) {
                loadBalancerService.getNodeMetrics()
            }
        }
    }

    fun getPrometheusMetrics(ctx: RoutingContext) {
        launch {
            val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()

            // Start flow tracking
            FlowTracker.startFlow(requestId, mapOf(
                "endpoint" to "Prometheus Metrics",
                "method" to ctx.request().method().name()
            ))

            logger.info { "Prometheus metrics requested (requestId: $requestId)" }

            try {
                // Update flow state
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)
                logService.log("info", "Prometheus metrics requested", mapOf("requestId" to requestId))

                // Get Prometheus metrics
                val metrics = adminService.getPrometheusMetrics()

                // Update flow state to completed
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED)

                // Send response
                ctx.response()
                    .putHeader("Content-Type", "text/plain")
                    .setStatusCode(200)
                    .end(metrics)

            } catch (e: Exception) {
                // Record error in flow tracking
                FlowTracker.recordError(
                    requestId,
                    "PrometheusMetricsError",
                    e.message ?: "Unknown error",
                    failFlow = true
                )

                logger.error(e) { "Failed to get Prometheus metrics (requestId: $requestId)" }
                logService.logError("Failed to get Prometheus metrics", e, mapOf("requestId" to requestId))

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getPerformanceMetrics(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "Performance Metrics",
                FlowTracker.FlowState.ANALYZING
            ) {
                performanceTracker.getPerformanceMetrics()
            }
        }
    }

    fun getRequestStatistics(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "Request Statistics",
                FlowTracker.FlowState.ANALYZING
            ) {
                adminService.getRequestStatistics()
            }
        }
    }

    fun getNodeHealth(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "Node Health",
                FlowTracker.FlowState.TASK_DECOMPOSED
            ) {
                adminService.getNodeHealth()
            }
        }
    }

    fun getSystemInfo(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "System Information",
                FlowTracker.FlowState.ANALYZING
            ) {
                adminService.getSystemInfo()
            }
        }
    }

    fun resetStatistics(ctx: RoutingContext) {
        launch {
            handleAdminRequest(
                ctx,
                "Reset Statistics",
                FlowTracker.FlowState.EXECUTING
            ) {
                adminService.resetStatistics()
                "Statistics reset successfully"
            }
        }
    }
}