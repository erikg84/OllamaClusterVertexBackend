package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.AgentService
import com.dallaslabs.services.LogService
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
 * Handler for agent-based operations
 */
class AgentHandler(
    private val vertx: Vertx,
    private val agentService: AgentService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    /**
     * Creates a new agent conversation
     */
    fun createConversation(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Create agent conversation requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/agents/conversations",
            "method" to "POST",
            "type" to "agent_conversation_create"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
            "analysisStartedAt" to System.currentTimeMillis()
        ))

        launch {
            logService.log("info", "Create agent conversation requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val model = body.getString("model", null)

                // Record conversation parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "queryLength" to query.length,
                    "preferredModel" to (model ?: "none")
                ))

                if (query.isBlank()) {
                    FlowTracker.recordError(requestId, "validation_error", "Query is required")
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                // Update state to MODEL_SELECTION if model is specified
                if (model != null) {
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                        "selectedModel" to model
                    ))
                }

                // Update state to AGENT_PROCESSING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.AGENT_PROCESSING, mapOf(
                    "processingStartedAt" to System.currentTimeMillis()
                ))

                val startTime = System.currentTimeMillis()
                val conversationId = agentService.createConversation(query, model)
                val processingTime = System.currentTimeMillis() - startTime

                // Record conversation creation result
                FlowTracker.recordMetrics(requestId, mapOf(
                    "conversationId" to conversationId,
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(
                    data = mapOf(
                        "conversationId" to conversationId
                    ),
                    message = "Agent conversation created successfully"
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
                logger.error(e) { "Failed to create agent conversation (requestId: $requestId)" }
                logService.logError("Failed to create agent conversation", e, mapOf("requestId" to requestId))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "conversation_creation_error", e.message ?: "Unknown error")

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Gets the state of an agent conversation
     */
    fun getConversation(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val conversationId = ctx.pathParam("id")
        logger.info { "Get agent conversation requested: $conversationId (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/agents/conversations/$conversationId",
            "method" to "GET",
            "type" to "agent_conversation_get",
            "conversationId" to conversationId
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        launch {
            logService.log("info", "Get agent conversation requested", mapOf(
                "requestId" to requestId,
                "conversationId" to conversationId
            ))
            try {
                val startTime = System.currentTimeMillis()
                val conversation = agentService.getConversation(conversationId)
                val processingTime = System.currentTimeMillis() - startTime

                if (conversation == null) {
                    FlowTracker.recordError(requestId, "conversation_not_found", "Conversation not found: $conversationId")
                    respondWithError(ctx, 404, "Conversation not found: $conversationId")
                    return@launch
                }

                // Record conversation metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "conversationState" to conversation.state.toString(),
                    "messagesCount" to conversation.messages.size,
                    "conversationAge" to (System.currentTimeMillis() - conversation.createdAt),
                    "isCompleted" to (conversation.state.toString() == "COMPLETED"),
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success(
                    data = mapOf(
                        "id" to conversation.id,
                        "state" to conversation.state,
                        "initialQuery" to conversation.initialQuery,
                        "messages" to conversation.messages.map { message ->
                            mapOf(
                                "role" to message.role,
                                "content" to message.content,
                                "timestamp" to message.timestamp
                            )
                        },
                        "createdAt" to conversation.createdAt,
                        "completedAt" to conversation.completedAt
                    )
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
                logger.error(e) { "Failed to get agent conversation: $conversationId (requestId: $requestId)" }
                logService.logError("Failed to get agent conversation", e, mapOf(
                    "requestId" to requestId,
                    "conversationId" to conversationId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "conversation_retrieval_error", e.message ?: "Unknown error")

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Adds a message to an existing conversation
     */
    fun addMessage(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        val conversationId = ctx.pathParam("id")
        logger.info { "Add message to agent conversation requested: $conversationId (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/agents/conversations/$conversationId/messages",
            "method" to "POST",
            "type" to "agent_message_add",
            "conversationId" to conversationId
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        launch {
            logService.log("info", "Add message to agent conversation requested", mapOf(
                "requestId" to requestId,
                "conversationId" to conversationId
            ))
            try {
                val body = ctx.body().asJsonObject()
                val message = body.getString("message")

                // Record message parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "messageLength" to message.length
                ))

                if (message.isBlank()) {
                    FlowTracker.recordError(requestId, "validation_error", "Message is required")
                    respondWithError(ctx, 400, "Message is required")
                    return@launch
                }

                // Update state to AGENT_PROCESSING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.AGENT_PROCESSING, mapOf(
                    "processingStartedAt" to System.currentTimeMillis()
                ))

                val startTime = System.currentTimeMillis()
                val success = agentService.addUserMessage(conversationId, message)
                val processingTime = System.currentTimeMillis() - startTime

                // Record processing result
                FlowTracker.recordMetrics(requestId, mapOf(
                    "success" to success,
                    "processingTimeMs" to processingTime
                ))

                if (!success) {
                    FlowTracker.recordError(requestId, "conversation_not_found", "Conversation not found: $conversationId")
                    respondWithError(ctx, 404, "Conversation not found: $conversationId")
                    return@launch
                }

                val response = ApiResponse.success<Unit>(
                    message = "Message added to conversation successfully"
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
                logger.error(e) { "Failed to add message to agent conversation: $conversationId (requestId: $requestId)" }
                logService.logError("Failed to add message to agent conversation", e, mapOf(
                    "requestId" to requestId,
                    "conversationId" to conversationId
                ))

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "message_add_error", e.message ?: "Unknown error")

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Cleanup old conversations
     */
    fun cleanupConversations(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Cleanup agent conversations requested (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/agents/cleanup",
            "method" to "POST",
            "type" to "agent_conversation_cleanup"
        ))

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING)

        launch {
            logService.log("info", "Cleanup agent conversations requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val maxAgeMs = body.getLong("maxAgeMs", 24 * 60 * 60 * 1000) // Default to 24 hours

                // Record cleanup parameters
                FlowTracker.recordMetrics(requestId, mapOf(
                    "maxAgeMs" to maxAgeMs,
                    "maxAgeDays" to (maxAgeMs / (24 * 60 * 60 * 1000.0))
                ))

                val startTime = System.currentTimeMillis()
                agentService.cleanupOldConversations(maxAgeMs)
                val processingTime = System.currentTimeMillis() - startTime

                // Record processing metrics
                FlowTracker.recordMetrics(requestId, mapOf(
                    "processingTimeMs" to processingTime
                ))

                val response = ApiResponse.success<Unit>(
                    message = "Old agent conversations cleaned up successfully"
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
                logger.error(e) { "Failed to cleanup agent conversations (requestId: $requestId)" }
                logService.logError("Failed to cleanup agent conversations", e, mapOf("requestId" to requestId))

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