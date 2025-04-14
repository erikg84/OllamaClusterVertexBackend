package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.ChatRequest
import com.dallaslabs.models.GenerateRequest
import com.dallaslabs.services.LogService
import com.dallaslabs.services.TaskDecompositionService
import com.dallaslabs.services.TaskStatus
import com.dallaslabs.tracking.FlowTracker
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for task decomposition operations
 */
class TaskDecompositionHandler(
    private val vertx: Vertx,
    private val taskDecompositionService: TaskDecompositionService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    /**
     * Decomposes a chat request into tasks
     */
    fun decomposeChatRequest(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Task decomposition requested for chat (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/decompose/chat",
            "method" to "POST",
            "type" to "chat_decomposition"
        ))

        launch {
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                    "analysisStartedAt" to System.currentTimeMillis()
                ))

                val request = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)
                logService.log("info", "Decomposing chat request", mapOf("requestId" to requestId))

                // Record metadata about request
                FlowTracker.recordMetrics(requestId, mapOf(
                    "messagesCount" to request.messages.size,
                    "charCount" to request.messages.sumOf { it.content.length },
                    "model" to request.model
                ))

                // Update state to TASK_DECOMPOSED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.TASK_DECOMPOSED)

                val startTime = System.currentTimeMillis()
                val workflow = taskDecompositionService.decomposeRequest(request)
                val processingTime = System.currentTimeMillis() - startTime

                // Record workflow details
                FlowTracker.recordMetrics(requestId, mapOf(
                    "workflowId" to workflow.id,
                    "taskCount" to workflow.tasks.size,
                    "strategy" to workflow.decompositionStrategy.toString(),
                    "decompositionTime" to processingTime
                ))

                val response = ApiResponse.success(
                    data = mapOf(
                        "workflowId" to workflow.id,
                        "strategy" to workflow.decompositionStrategy,
                        "taskCount" to workflow.tasks.size,
                        "tasks" to workflow.tasks.map { task ->
                            mapOf(
                                "id" to task.id,
                                "type" to task.type,
                                "content" to task.content,
                                "dependencies" to task.dependencies,
                                "status" to task.status
                            )
                        }
                    ),
                    message = "Request decomposed into ${workflow.tasks.size} tasks"
                )

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
                logger.error(e) { "Failed to decompose chat request (requestId: $requestId)" }
                logService.logError("Failed to decompose chat request", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "decomposition_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Decomposes a generate request into tasks
     */
    fun decomposeGenerateRequest(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Task decomposition requested for generate (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/decompose/generate",
            "method" to "POST",
            "type" to "generate_decomposition"
        ))

        launch {
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                    "analysisStartedAt" to System.currentTimeMillis()
                ))

                val request = ctx.body().asJsonObject().mapTo(GenerateRequest::class.java)
                logService.log("info", "Decomposing generate request", mapOf("requestId" to requestId))

                // Record metadata about request
                FlowTracker.recordMetrics(requestId, mapOf(
                    "promptLength" to request.prompt.length,
                    "model" to request.model
                ))

                // Update state to TASK_DECOMPOSED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.TASK_DECOMPOSED)

                val startTime = System.currentTimeMillis()
                val workflow = taskDecompositionService.decomposeRequest(request)
                val processingTime = System.currentTimeMillis() - startTime

                // Record workflow details
                FlowTracker.recordMetrics(requestId, mapOf(
                    "workflowId" to workflow.id,
                    "taskCount" to workflow.tasks.size,
                    "strategy" to workflow.decompositionStrategy.toString(),
                    "decompositionTime" to processingTime
                ))

                val response = ApiResponse.success(
                    data = mapOf(
                        "workflowId" to workflow.id,
                        "strategy" to workflow.decompositionStrategy,
                        "taskCount" to workflow.tasks.size,
                        "tasks" to workflow.tasks.map { task ->
                            mapOf(
                                "id" to task.id,
                                "type" to task.type,
                                "content" to task.content,
                                "dependencies" to task.dependencies,
                                "status" to task.status
                            )
                        }
                    ),
                    message = "Request decomposed into ${workflow.tasks.size} tasks"
                )

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
                logger.error(e) { "Failed to decompose generate request (requestId: $requestId)" }
                logService.logError("Failed to decompose generate request", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "decomposition_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets information about a specific workflow
     */
    fun getWorkflow(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val workflowId = ctx.pathParam("id")
        logger.info { "Workflow info requested: $workflowId (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/${workflowId}",
            "method" to "GET",
            "type" to "workflow_info",
            "workflowId" to workflowId
        ))

        launch {
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val workflow = taskDecompositionService.getWorkflow(workflowId)

                if (workflow == null) {
                    // Record error for non-existent workflow
                    FlowTracker.recordError(requestId, "workflow_not_found", "Workflow not found: $workflowId")

                    val response = ApiResponse.error<Nothing>("Workflow not found: $workflowId")

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(404)
                        .end(JsonObject.mapFrom(response).encode())
                    return@launch
                }

                logService.log("info", "Retrieved workflow info", mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId
                ))

                // Record workflow metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "workflowStrategy" to workflow.decompositionStrategy.toString(),
                    "taskCount" to workflow.tasks.size,
                    "completedTasks" to workflow.tasks.count { it.status == TaskStatus.COMPLETED },
                    "failedTasks" to workflow.tasks.count { it.status == TaskStatus.FAILED },
                    "pendingTasks" to workflow.tasks.count { it.status == TaskStatus.PENDING },
                    "runningTasks" to workflow.tasks.count { it.status == TaskStatus.RUNNING },
                    "workflowAge" to (System.currentTimeMillis() - workflow.createdAt)
                ))

                val response = ApiResponse.success(
                    data = mapOf(
                        "workflowId" to workflow.id,
                        "strategy" to workflow.decompositionStrategy,
                        "createdAt" to workflow.createdAt,
                        "completedAt" to workflow.completedAt,
                        "isComplete" to taskDecompositionService.isWorkflowComplete(workflowId),
                        "tasks" to workflow.tasks.map { task ->
                            mapOf(
                                "id" to task.id,
                                "type" to task.type,
                                "content" to task.content,
                                "status" to task.status,
                                "dependencies" to task.dependencies,
                                "startedAt" to task.startedAt,
                                "completedAt" to task.completedAt
                            )
                        }
                    )
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get workflow info: $workflowId (requestId: $requestId)" }
                logService.logError("Failed to get workflow info", e, mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "workflow_retrieval_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets the next executable tasks for a workflow
     */
    fun getNextTasks(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val workflowId = ctx.pathParam("id")
        logger.info { "Next executable tasks requested: $workflowId (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/${workflowId}/next",
            "method" to "GET",
            "type" to "next_tasks",
            "workflowId" to workflowId
        ))

        launch {
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val nextTasks = taskDecompositionService.getNextExecutableTasks(workflowId)

                logService.log("info", "Retrieved next executable tasks", mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId,
                    "taskCount" to nextTasks.size
                ))

                // Record metrics about next tasks
                FlowTracker.recordMetrics(requestId, mapOf(
                    "nextTasksCount" to nextTasks.size,
                    "nextTaskTypes" to nextTasks.map { it.type.toString() }
                ))

                val response = ApiResponse.success(
                    data = mapOf(
                        "workflowId" to workflowId,
                        "nextTasks" to nextTasks.map { task ->
                            mapOf(
                                "id" to task.id,
                                "type" to task.type,
                                "content" to task.content,
                                "status" to task.status,
                                "dependencies" to task.dependencies
                            )
                        }
                    ),
                    message = "${nextTasks.size} tasks ready for execution"
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get next tasks: $workflowId (requestId: $requestId)" }
                logService.logError("Failed to get next tasks", e, mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "next_tasks_retrieval_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Updates the status of a task
     */
    fun updateTaskStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val workflowId = ctx.pathParam("workflowId")
        val taskId = ctx.pathParam("taskId")

        logger.info { "Task status update requested: $workflowId/$taskId (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/${workflowId}/task/${taskId}",
            "method" to "PUT",
            "type" to "task_update",
            "workflowId" to workflowId,
            "taskId" to taskId
        ))

        launch {
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val body = ctx.body().asJsonObject()
                val statusString = body.getString("status")
                val status = try {
                    TaskStatus.valueOf(statusString.uppercase())
                } catch (e: Exception) {
                    // Record validation error
                    FlowTracker.recordError(requestId, "invalid_status", "Invalid status: $statusString")
                    throw IllegalArgumentException("Invalid status: $statusString")
                }

                val result = body.getJsonObject("result")

                // Record task update metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "newStatus" to status.toString(),
                    "hasResult" to (result != null)
                ))

                taskDecompositionService.updateTaskStatus(workflowId, taskId, status, result)

                logService.log("info", "Updated task status", mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId,
                    "taskId" to taskId,
                    "status" to status.name
                ))

                val response = ApiResponse.success<Unit>(
                    message = "Task status updated successfully"
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to update task status: $workflowId/$taskId (requestId: $requestId)" }
                logService.logError("Failed to update task status", e, mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId,
                    "taskId" to taskId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "task_update_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Gets the final result of a workflow
     */
    fun getFinalResult(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val workflowId = ctx.pathParam("id")
        logger.info { "Final result requested: $workflowId (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/${workflowId}/result",
            "method" to "GET",
            "type" to "final_result",
            "workflowId" to workflowId
        ))

        launch {
            try {
                // Update state to SYNTHESIZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.SYNTHESIZING, mapOf(
                    "workflowId" to workflowId
                ))

                val finalResult = taskDecompositionService.getFinalResult(workflowId)

                if (finalResult == null) {
                    // Record error for unavailable result
                    FlowTracker.recordError(requestId, "result_not_available",
                        "Workflow not complete or result not available")

                    val response = ApiResponse.error<Nothing>("Workflow not complete or result not available")

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(404)
                        .end(JsonObject.mapFrom(response).encode())
                    return@launch
                }

                logService.log("info", "Retrieved final workflow result", mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId
                ))

                // Record metrics about the final result
                FlowTracker.recordMetrics(requestId, mapOf(
                    "resultSize" to finalResult.encode().length
                ))

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(finalResult.encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get final result: $workflowId (requestId: $requestId)" }
                logService.logError("Failed to get final result", e, mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "result_retrieval_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }

    /**
     * Cleans up old workflow data
     */
    fun cleanupWorkflows(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Workflow cleanup requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/workflow/cleanup",
            "method" to "POST",
            "type" to "cleanup"
        ))

        launch {
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val body = ctx.body().asJsonObject()
                val maxAgeMs = body.getLong("maxAgeMs", 24 * 60 * 60 * 1000) // Default to 24 hours

                // Record cleanup parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "maxAgeMs" to maxAgeMs,
                    "maxAgeDays" to (maxAgeMs / (24 * 60 * 60 * 1000.0))
                ))

                taskDecompositionService.cleanupOldWorkflows(maxAgeMs)

                logService.log("info", "Cleaned up old workflows", mapOf(
                    "requestId" to requestId,
                    "maxAgeMs" to maxAgeMs
                ))

                val response = ApiResponse.success<Unit>(
                    message = "Old workflows cleaned up successfully"
                )

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "statusCode" to 200
                ))

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup workflows (requestId: $requestId)" }
                logService.logError("Failed to cleanup workflows", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "cleanup_error", e.message ?: "Unknown error")

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}