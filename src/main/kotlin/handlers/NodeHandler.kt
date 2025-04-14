package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.NodeStatus
import com.dallaslabs.services.LogService
import com.dallaslabs.services.NodeService
import com.dallaslabs.tracking.FlowTracker
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for node-related requests
 */
class NodeHandler(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    fun listNodes(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "List nodes requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/nodes",
            "method" to "GET",
            "type" to "node_list"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        launch {
            logService.log("info", "List nodes requested", mapOf(
                "remoteAddress" to ctx.request().remoteAddress().host(),
                "requestId" to requestId
            ))

            try {
                val startTime = System.currentTimeMillis()
                val nodes = nodeService.getNodes()
                val processingTime = System.currentTimeMillis() - startTime

                // Record node metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "nodeCount" to nodes.size,
                    "gpuNodes" to nodes.count { it.type == "gpu" },
                    "cpuNodes" to nodes.count { it.type == "cpu" },
                    "macosNodes" to nodes.count { it.platform == "macos" },
                    "windowsNodes" to nodes.count { it.platform == "windows" },
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(nodes)

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to list nodes (requestId: $requestId)" }
                logService.logError("Failed to list nodes", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "node_list_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getNodeModels(ctx: RoutingContext) {
        val nodeName = ctx.pathParam("name")
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Node models requested: $nodeName (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/nodes/$nodeName/models",
            "method" to "GET",
            "type" to "node_models",
            "nodeName" to nodeName
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        // Update state to NODE_SELECTION since we know the target node
        FlowTracker.updateState(requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
            "selectedNode" to nodeName
        ))

        launch {
            logService.log("info", "Node models requested", mapOf("nodeName" to nodeName, "requestId" to requestId))

            try {
                val startTime = System.currentTimeMillis()
                val models = nodeService.getNodeModels(nodeName).coAwait()
                val processingTime = System.currentTimeMillis() - startTime

                // Record model metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "modelCount" to models.size,
                    "modelTypes" to models.map { it.type }.distinct(),
                    "largestModel" to (models.maxByOrNull { it.size }?.id ?: "none"),
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(models)

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get models for node $nodeName (requestId: $requestId)" }
                logService.logError("Failed to get models for node", e, mapOf("nodeName" to nodeName, "requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "node_models_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getNodeStatus(ctx: RoutingContext) {
        val nodeName = ctx.pathParam("name")
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Node status requested: $nodeName (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/nodes/$nodeName",
            "method" to "GET",
            "type" to "node_status",
            "nodeName" to nodeName
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        // Update state to NODE_SELECTION since we know the target node
        FlowTracker.updateState(requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
            "selectedNode" to nodeName
        ))

        launch {
            logService.log("info", "Node status requested", mapOf(
                "nodeName" to nodeName,
                "requestId" to requestId
            ))

            try {
                val startTime = System.currentTimeMillis()
                val status = nodeService.getNodeStatus(nodeName).coAwait()
                val processingTime = System.currentTimeMillis() - startTime

                // Record status metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "nodeStatus" to status.status,
                    "nodeType" to status.node.type,
                    "nodePlatform" to status.node.platform,
                    "nodeCapabilities" to status.node.capabilities,
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success<NodeStatus>(status)

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get node status for $nodeName (requestId: $requestId)" }
                logService.logError("Failed to get node status", e, mapOf(
                    "nodeName" to nodeName,
                    "requestId" to requestId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "node_status_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    fun getAllNodesStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "All nodes status requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/nodes/status",
            "method" to "GET",
            "type" to "all_nodes_status"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        launch {
            logService.log("info", "All nodes status requested", mapOf(
                "remoteAddress" to ctx.request().remoteAddress().host(),
                "requestId" to requestId
            ))

            try {
                val startTime = System.currentTimeMillis()
                val statuses = nodeService.getAllNodesStatus().coAwait()
                val processingTime = System.currentTimeMillis() - startTime

                // Record node status metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "totalNodes" to statuses.size,
                    "onlineNodes" to statuses.count { it.status == "online" },
                    "offlineNodes" to statuses.count { it.status == "offline" },
                    "errorNodes" to statuses.count { it.status == "error" },
                    "onlineGpuNodes" to statuses.count { it.status == "online" && it.node.type == "gpu" },
                    "onlineCpuNodes" to statuses.count { it.status == "online" && it.node.type == "cpu" },
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(statuses)

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "responseTime" to processingTime,
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get all nodes status (requestId: $requestId)" }
                logService.logError("Failed to get all nodes status", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "all_nodes_status_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}