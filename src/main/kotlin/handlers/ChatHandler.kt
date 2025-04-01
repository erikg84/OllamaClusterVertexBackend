package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.ChatRequest
import com.dallaslabs.models.Node
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Handler for chat requests
 */
class ChatHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val nodes: List<Node>
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    private val webClient = WebClient.create(vertx, WebClientOptions()
        .setConnectTimeout(30000)
        .setIdleTimeout(60000)
    )

    /**
     * Handles chat requests
     */
    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        try {
            val req = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)

            // Validate request
            if (req.model.isBlank()) {
                respondWithError(ctx, 400, "Model is required")
                return
            }

            if (req.messages.isEmpty()) {
                respondWithError(ctx, 400, "At least one message is required")
                return
            }

            if (req.stream) {
                respondWithError(ctx, 400, "Streaming is not supported yet")
                return
            }

            logger.info {
                "Chat request received: model=${req.model}, " +
                        "messagesCount=${req.messages.size}, stream=${req.stream} (requestId: $requestId)"
            }

            // Process the request
            launch {
                try {
                    val result = queue.add<JsonObject> {
                        // Select appropriate node based on model and availability
                        // For now just use the first node
                        if (nodes.isEmpty()) {
                            throw IllegalStateException("No nodes available")
                        }

                        val node = nodes[0]

                        logger.info {
                            "Forwarding chat request to node: ${node.name} " +
                                    "(requestId: $requestId)"
                        }

                        // Forward request to the selected node
                        val response = webClient.post(node.port, node.host, "/chat")
                            .putHeader("Content-Type", "application/json")
                            .putHeader("X-Request-ID", requestId)
                            .sendBuffer(Buffer.buffer(JsonObject.mapFrom(req).encode()))
                            .coAwait()

                        if (response.statusCode() != 200) {
                            throw RuntimeException("Node returned status ${response.statusCode()}")
                        }

                        response.bodyAsJsonObject()
                    }.coAwait()

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(result.encode())

                } catch (e: Exception) {
                    logger.error(e) { "Failed to process chat request (requestId: $requestId)" }
                    respondWithError(ctx, 500, e.message ?: "Unknown error")
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse request (requestId: $requestId)" }
            respondWithError(ctx, 400, "Invalid request: ${e.message}")
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
