package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.services.OrchestrationPatternService
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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/patterns/ensemble",
            "method" to "POST",
            "pattern" to "MODEL_ENSEMBLE",
            "type" to "orchestration"
        ))

        launch {
            logService.log("info", "Model ensemble execution requested", mapOf("requestId" to requestId))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                    "analysisStartedAt" to System.currentTimeMillis()
                ))

                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val models = body.getJsonArray("models")?.map { it as String }
                val ensembleSize = body.getInteger("ensembleSize", 3)
                val consensusThreshold = body.getDouble("consensusThreshold", 0.7)

                // Record request parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "queryLength" to query.length,
                    "requestedModels" to (models?.joinToString() ?: "auto"),
                    "ensembleSize" to ensembleSize,
                    "consensusThreshold" to consensusThreshold
                ))

                if (query.isBlank()) {
                    FlowTracker.recordError(requestId, "validation_error", "Query is required")
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                // Update state to MODEL_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                    "modelsRequested" to (models?.size ?: ensembleSize)
                ))

                // Update state to ORCHESTRATING before execution
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ORCHESTRATING, mapOf(
                    "orchestrationStartedAt" to System.currentTimeMillis(),
                    "orchestrationType" to "MODEL_ENSEMBLE"
                ))

                val startTime = System.currentTimeMillis()
                val result = orchestrationPatternService.executeModelEnsemble(
                    query, models, ensembleSize, consensusThreshold, requestId
                )
                val processingTime = System.currentTimeMillis() - startTime

                // Record execution metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "executionId" to result.id,
                    "completionsCount" to result.completions.size,
                    "actualModelsUsed" to result.completions.map { it.model },
                    "consensusReached" to (result.completions.size > 1),
                    "executionTimeMs" to result.executionTimeMs,
                    "outputLength" to result.consensusOutput.length
                ))

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
                logger.error(e) { "Failed to execute model ensemble (requestId: $requestId)" }
                logService.logError("Failed to execute model ensemble", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "ensemble_execution_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/patterns/debate",
            "method" to "POST",
            "pattern" to "DEBATE",
            "type" to "orchestration"
        ))

        launch {
            logService.log("info", "Debate pattern execution requested", mapOf("requestId" to requestId))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                    "analysisStartedAt" to System.currentTimeMillis()
                ))

                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val models = body.getJsonArray("models")?.map { it as String }
                val debateRounds = body.getInteger("debateRounds", 3)

                // Record request parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "queryLength" to query.length,
                    "requestedModels" to (models?.joinToString() ?: "auto"),
                    "debateRounds" to debateRounds
                ))

                if (query.isBlank()) {
                    FlowTracker.recordError(requestId, "validation_error", "Query is required")
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                // Update state to MODEL_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                    "modelsRequested" to (models?.size ?: 2)
                ))

                // Update state to ORCHESTRATING before execution
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ORCHESTRATING, mapOf(
                    "orchestrationStartedAt" to System.currentTimeMillis(),
                    "orchestrationType" to "DEBATE",
                    "rounds" to debateRounds
                ))

                val startTime = System.currentTimeMillis()
                val result = orchestrationPatternService.executeDebatePattern(
                    query, debateRounds, models, requestId
                )
                val processingTime = System.currentTimeMillis() - startTime

                // Record execution metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "executionId" to result.id,
                    "debateMessageCount" to result.debateMessages.size,
                    "actualModelsUsed" to result.debateMessages.map { it.model }.distinct(),
                    "actualRoundsCompleted" to result.debateMessages.maxOf { it.round } + 1,
                    "executionTimeMs" to result.executionTimeMs,
                    "outputLength" to result.finalSynthesis.length
                ))

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
                logger.error(e) { "Failed to execute debate pattern (requestId: $requestId)" }
                logService.logError("Failed to execute debate pattern", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "debate_execution_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/patterns/maestro",
            "method" to "POST",
            "pattern" to "MAESTRO",
            "type" to "orchestration"
        ))

        launch {
            logService.log("info", "MAESTRO workflow execution requested", mapOf("requestId" to requestId))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                    "analysisStartedAt" to System.currentTimeMillis()
                ))

                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val preferredModel = body.getString("preferredModel", null)

                // Record request parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "queryLength" to query.length,
                    "preferredModel" to (preferredModel ?: "none")
                ))

                if (query.isBlank()) {
                    FlowTracker.recordError(requestId, "validation_error", "Query is required")
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                // Update state to MODEL_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                    "preferredModel" to (preferredModel ?: "none")
                ))

                // Update state to AGENT_PROCESSING before execution
                FlowTracker.updateState(requestId, FlowTracker.FlowState.AGENT_PROCESSING, mapOf(
                    "processingStartedAt" to System.currentTimeMillis(),
                    "orchestrationType" to "MAESTRO"
                ))

                val startTime = System.currentTimeMillis()
                val result = orchestrationPatternService.executeMAESTROWorkflow(
                    query, preferredModel, requestId
                )
                val processingTime = System.currentTimeMillis() - startTime

                // Update to SYNTHESIZING state
                FlowTracker.updateState(requestId, FlowTracker.FlowState.SYNTHESIZING, mapOf(
                    "agentsUsed" to result.agents.map { it.agentType }.distinct().size,
                    "conversationId" to result.conversationId
                ))

                // Record execution metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "executionId" to result.id,
                    "agentContributionsCount" to result.agents.size,
                    "agentTypes" to result.agents.map { it.agentType }.distinct(),
                    "executionTimeMs" to result.executionTimeMs,
                    "outputLength" to result.finalOutput.length
                ))

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
                logger.error(e) { "Failed to execute MAESTRO workflow (requestId: $requestId)" }
                logService.logError("Failed to execute MAESTRO workflow", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "maestro_execution_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/patterns/execution/${executionId}",
            "method" to "GET",
            "type" to "execution_status",
            "executionId" to executionId
        ))

        launch {
            logService.log("info", "Get execution status requested", mapOf(
                "requestId" to requestId,
                "executionId" to executionId
            ))
            try {
                // Update state to ANALYZING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

                val execution = orchestrationPatternService.getExecutionStatus(executionId)

                if (execution == null) {
                    FlowTracker.recordError(requestId, "execution_not_found", "Execution not found: $executionId")
                    respondWithError(ctx, 404, "Execution not found: $executionId")
                    return@launch
                }

                // Record execution details
                FlowTracker.recordMetrics(requestId, mapOf(
                    "pattern" to execution.pattern.toString(),
                    "state" to execution.state.toString(),
                    "modelsCount" to execution.models.size,
                    "executionAge" to (System.currentTimeMillis() - execution.startTime),
                    "isCompleted" to (execution.state.toString() == "COMPLETED"),
                    "hasResult" to (execution.result != null)
                ))

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

                // Update state to COMPLETED
                FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                    "statusCode" to 200
                ))

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

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "execution_status_error", e.message ?: "Unknown error")

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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/patterns/cleanup",
            "method" to "POST",
            "type" to "maintenance"
        ))

        launch {
            logService.log("info", "Cleanup executions requested", mapOf("requestId" to requestId))
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

                orchestrationPatternService.cleanupOldExecutions(maxAgeMs)

                logService.log("info", "Cleaned up old executions", mapOf(
                    "requestId" to requestId,
                    "maxAgeMs" to maxAgeMs
                ))

                val response = ApiResponse.success<Unit>(
                    message = "Old executions cleaned up successfully"
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
                logger.error(e) { "Failed to cleanup executions (requestId: $requestId)" }
                logService.logError("Failed to cleanup executions", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "cleanup_error", e.message ?: "Unknown error")

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