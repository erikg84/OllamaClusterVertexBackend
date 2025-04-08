package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.ChatRequest
import com.dallaslabs.models.GenerateRequest
import com.dallaslabs.services.LogService
import com.dallaslabs.services.TaskDecompositionService
import com.dallaslabs.services.TaskStatus
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

        launch {
            try {
                val request = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)
                logService.log("info", "Decomposing chat request", mapOf("requestId" to requestId))

                val workflow = taskDecompositionService.decomposeRequest(request)

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

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to decompose chat request (requestId: $requestId)" }
                logService.logError("Failed to decompose chat request", e, mapOf("requestId" to requestId))

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

        launch {
            try {
                val request = ctx.body().asJsonObject().mapTo(GenerateRequest::class.java)
                logService.log("info", "Decomposing generate request", mapOf("requestId" to requestId))

                val workflow = taskDecompositionService.decomposeRequest(request)

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

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to decompose generate request (requestId: $requestId)" }
                logService.logError("Failed to decompose generate request", e, mapOf("requestId" to requestId))

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

        launch {
            try {
                val workflow = taskDecompositionService.getWorkflow(workflowId)

                if (workflow == null) {
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

        launch {
            try {
                val nextTasks = taskDecompositionService.getNextExecutableTasks(workflowId)

                logService.log("info", "Retrieved next executable tasks", mapOf(
                    "requestId" to requestId,
                    "workflowId" to workflowId,
                    "taskCount" to nextTasks.size
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

        launch {
            try {
                val body = ctx.body().asJsonObject()
                val statusString = body.getString("status")
                val status = try {
                    TaskStatus.valueOf(statusString.uppercase())
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid status: $statusString")
                }

                val result = body.getJsonObject("result")

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

        launch {
            try {
                val finalResult = taskDecompositionService.getFinalResult(workflowId)

                if (finalResult == null) {
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

        launch {
            try {
                val body = ctx.body().asJsonObject()
                val maxAgeMs = body.getLong("maxAgeMs", 24 * 60 * 60 * 1000) // Default to 24 hours

                taskDecompositionService.cleanupOldWorkflows(maxAgeMs)

                logService.log("info", "Cleaned up old workflows", mapOf(
                    "requestId" to requestId,
                    "maxAgeMs" to maxAgeMs
                ))

                val response = ApiResponse.success<Unit>(
                    message = "Old workflows cleaned up successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup workflows (requestId: $requestId)" }
                logService.logError("Failed to cleanup workflows", e, mapOf("requestId" to requestId))

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}