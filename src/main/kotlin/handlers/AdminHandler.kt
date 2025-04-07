package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.AdminService
import com.dallaslabs.services.LoadBalancerService
import com.dallaslabs.services.LogService
import com.dallaslabs.services.PerformanceTrackerService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Handler for admin operations
 */
class AdminHandler(
    private val vertx: Vertx,
    private val adminService: AdminService,
    private val loadBalancerService: LoadBalancerService,
    private val performanceTracker: PerformanceTrackerService,
    logService: LogService,
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    /**
     * Gets general metrics
     */
    fun getMetrics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Metrics requested (requestId: $requestId)" }

        launch {
            try {
                val metrics = adminService.getMetrics()
                val response = ApiResponse.success(metrics)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get metrics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getLoadBalancingMetrics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Load balancing metrics requested (requestId: $requestId)" }

        launch {
            try {
                val metrics = loadBalancerService.getNodeMetrics()
                val response = ApiResponse.success(metrics)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get load balancing metrics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets Prometheus-formatted metrics
     */
    fun getPrometheusMetrics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Prometheus metrics requested (requestId: $requestId)" }

        launch {
            try {
                val metrics = adminService.getPrometheusMetrics()

                ctx.response()
                    .putHeader("Content-Type", "text/plain")
                    .setStatusCode(200)
                    .end(metrics)
            } catch (e: Exception) {
                logger.error(e) { "Failed to get Prometheus metrics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets performance metrics
     */
    fun getPerformanceMetrics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Performance metrics requested (requestId: $requestId)" }

        launch {
            try {
                val metrics = performanceTracker.getPerformanceMetrics()
                val response = ApiResponse.success(metrics)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get performance metrics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets request statistics
     */
    fun getRequestStatistics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Request statistics requested (requestId: $requestId)" }

        launch {
            try {
                val stats = adminService.getRequestStatistics()
                val response = ApiResponse.success(stats)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get request statistics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets node health information
     */
    fun getNodeHealth(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Node health requested (requestId: $requestId)" }

        launch {
            try {
                val health = adminService.getNodeHealth()
                val response = ApiResponse.success(health)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get node health (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets system information
     */
    fun getSystemInfo(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "System information requested (requestId: $requestId)" }

        launch {
            try {
                val sysInfo = adminService.getSystemInfo()
                val response = ApiResponse.success(sysInfo)

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get system information (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Resets statistics
     */
    fun resetStatistics(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Reset statistics requested (requestId: $requestId)" }

        launch {
            try {
                adminService.resetStatistics()
                val response = ApiResponse.success<Unit>(message = "Statistics reset successfully")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to reset statistics (requestId: $requestId)" }
                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
