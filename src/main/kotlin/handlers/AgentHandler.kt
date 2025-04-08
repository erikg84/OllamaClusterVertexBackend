package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.AgentService
import com.dallaslabs.services.LogService
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

        launch {
            logService.log("info", "Create agent conversation requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val query = body.getString("query")
                val model = body.getString("model", null)

                if (query.isBlank()) {
                    respondWithError(ctx, 400, "Query is required")
                    return@launch
                }

                val conversationId = agentService.createConversation(query, model)

                val response = ApiResponse.success(
                    data = mapOf(
                        "conversationId" to conversationId
                    ),
                    message = "Agent conversation created successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to create agent conversation (requestId: $requestId)" }
                logService.logError("Failed to create agent conversation", e, mapOf("requestId" to requestId))

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

        launch {
            logService.log("info", "Get agent conversation requested", mapOf(
                "requestId" to requestId,
                "conversationId" to conversationId
            ))
            try {
                val conversation = agentService.getConversation(conversationId)

                if (conversation == null) {
                    respondWithError(ctx, 404, "Conversation not found: $conversationId")
                    return@launch
                }

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

        launch {
            logService.log("info", "Add message to agent conversation requested", mapOf(
                "requestId" to requestId,
                "conversationId" to conversationId
            ))
            try {
                val body = ctx.body().asJsonObject()
                val message = body.getString("message")

                if (message.isBlank()) {
                    respondWithError(ctx, 400, "Message is required")
                    return@launch
                }

                val success = agentService.addUserMessage(conversationId, message)

                if (!success) {
                    respondWithError(ctx, 404, "Conversation not found: $conversationId")
                    return@launch
                }

                val response = ApiResponse.success<Unit>(
                    message = "Message added to conversation successfully"
                )

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

        launch {
            logService.log("info", "Cleanup agent conversations requested", mapOf("requestId" to requestId))
            try {
                val body = ctx.body().asJsonObject()
                val maxAgeMs = body.getLong("maxAgeMs", 24 * 60 * 60 * 1000) // Default to 24 hours

                agentService.cleanupOldConversations(maxAgeMs)

                val response = ApiResponse.success<Unit>(
                    message = "Old agent conversations cleaned up successfully"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup agent conversations (requestId: $requestId)" }
                logService.logError("Failed to cleanup agent conversations", e, mapOf("requestId" to requestId))

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