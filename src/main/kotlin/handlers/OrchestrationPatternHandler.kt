package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.services.OrchestrationPatternService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Handler for orchestration pattern operations
 */
class OrchestrationPatternHandler(
    private val vertx: Vertx,
    private val orchestrationPatternService: OrchestrationPatternService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    /**
     * Execute model ensemble pattern
     */
    fun executeModelEnsemble(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Model ensemble execution requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Model ensemble execution requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val models = body.getJsonArray("models")?.map { it as String }
                val ensembleSize = body.getInteger("ensembleSize", 3)
                val consensusThreshold = body.getDouble("consensusThreshold", 0.7)

                if (query.isBlank()) {
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                val result = orchestrationPatternService.executeModelEnsemble(
                    query, models, ensembleSize, consensusThreshold, requestId
                )

                val response = ApiResponse.success(
                    data = mapOf(
                        "id" to result.id,
                        "consensusOutput" to result.consensusOutput,
                        "completions" to result.completions.map { completion ->
                            mapOf(
                                "model" to completion.model,
                                "text" to completion.text,
                                "score" to completion.score
                            )
                        },
                        "executionTimeMs" to result.executionTimeMs
                    ),
                    message = "Model ensemble executed successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute model ensemble (requestId: $requestId)" }
                logService.logError("Failed to execute model ensemble", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Execute debate pattern
     */
    fun executeDebatePattern(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Debate pattern execution requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Debate pattern execution requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val models = body.getJsonArray("models")?.map { it as String }
                val debateRounds = body.getInteger("debateRounds", 3)

                if (query.isBlank()) {
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                val result = orchestrationPatternService.executeDebatePattern(
                    query, debateRounds, models, requestId
                )

                val response = ApiResponse.success(
                    data = mapOf(
                        "id" to result.id,
                        "finalSynthesis" to result.finalSynthesis,
                        "debateMessages" to result.debateMessages.map { message ->
                            mapOf(
                                "model" to message.model,
                                "content" to message.content,
                                "round" to message.round
                            )
                        },
                        "executionTimeMs" to result.executionTimeMs
                    ),
                    message = "Debate pattern executed successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute debate pattern (requestId: $requestId)" }
                logService.logError("Failed to execute debate pattern", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Execute MAESTRO workflow
     */
    fun executeMAESTROWorkflow(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "MAESTRO workflow execution requested (requestId: $requestId)" }

        launch {
            logService.log("info", "MAESTRO workflow execution requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val preferredModel = body.getString("preferredModel", null)

                if (query.isBlank()) {
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                val result = orchestrationPatternService.executeMAESTROWorkflow(
                    query, preferredModel, requestId
                )

                val response = ApiResponse.success(
                    data = mapOf(
                        "id" to result.id,
                        "conversationId" to result.conversationId,
                        "finalOutput" to result.finalOutput,
                        "agents" to result.agents.map { agent ->
                            mapOf(
                                "agentType" to agent.agentType,
                                "content" to agent.content,
                                "timestamp" to agent.timestamp
                            )
                        },
                        "executionTimeMs" to result.executionTimeMs
                    ),
                    message = "MAESTRO workflow executed successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to execute MAESTRO workflow (requestId: $requestId)" }
                logService.logError("Failed to execute MAESTRO workflow", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Get execution status
     */
    fun getExecutionStatus(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val executionId = ctx.pathParam("id")
        logger.info { "Get execution status requested: $executionId (requestId: $requestId)" }

        launch {
            logService.log("info", "Get execution status requested", mapOf(
                "requestId" to requestId,
                "executionId" to executionId
            ))
            try {
                val execution = orchestrationPatternService.getExecutionStatus(executionId)

                if (execution == null) {
                    respondWithError(ctx, 404, "Execution not found: $executionId")
                    return@launch
                }

                val response = ApiResponse.success(
                    data = mapOf(
                        "id" to execution.id,
                        "pattern" to execution.pattern,
                        "query" to execution.query,
                        "models" to execution.models,
                        "state" to execution.state,
                        "startTime" to execution.startTime,
                        "endTime" to execution.endTime,
                        "result" to execution.result
                    )
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get execution status: $executionId (requestId: $requestId)" }
                logService.logError("Failed to get execution status", e, mapOf(
                    "requestId" to requestId,
                    "executionId" to executionId
                ))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Cleanup old executions
     */
    fun cleanupExecutions(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cleanup executions requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Cleanup executions requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val maxAgeMs = body.getLong("maxAgeMs", 24 * 60 * 60 * 1000) // Default to 24 hours

                orchestrationPatternService.cleanupOldExecutions(maxAgeMs)

                val response = ApiResponse.success<Unit>(
                    message = "Old executions cleaned up successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup executions (requestId: $requestId)" }
                logService.logError("Failed to cleanup executions", e, mapOf("requestId" to requestId))

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    private fun respondWithError(ctx: RoutingContext, statusCode: Int, message: String) {
        val response = ApiResponse.error<Nothing>(message)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(statusCode)
            .end(JsonObject.mapFrom(response).encode())
    }
}