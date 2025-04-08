package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.ChatRequest
import com.dallaslabs.models.Node
import com.dallaslabs.services.*
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

class ChatHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val nodeService: NodeService,
    private val nodes: List<Node>,
    private val performanceTracker: PerformanceTrackerService,
    private val loadBalancer: LoadBalancerService,
    private val logService: LogService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    private val webClient = WebClient.create(vertx, WebClientOptions()
        .setConnectTimeout(30000)
        .setIdleTimeout(60000)
    )

    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        try {
            val req = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)

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

            launch {
                logService.log("info", "Chat request received", mapOf(
                    "model" to req.model,
                    "messagesCount" to req.messages.size,
                    "stream" to req.stream,
                    "requestId" to requestId
                ))

                try {
                    val startTime = System.currentTimeMillis()
                    val result = queue.add<JsonObject> {
                        if (!modelRegistry.isModelAvailable(req.model)) {
                            throw IllegalArgumentException("Model ${req.model} is not available on any node")
                        }

                        val nodeName = nodeService.getBestNodeForModel(req.model)

                        if (nodeName == null) {
                            throw IllegalStateException("No suitable node found for model ${req.model}")
                        }

                        val node = nodes.find { it.name == nodeName }
                            ?: throw IllegalStateException("Node $nodeName not found in configuration")

                        logger.info {
                            "Selected node ${node.name} for chat request with model ${req.model} " +
                                    "(requestId: $requestId)"
                        }

                        logService.log("info", "Selected node for chat request", mapOf(
                            "modelId" to req.model,
                            "nodeName" to node.name,
                            "requestId" to requestId
                        ))

                        val requestWithNode = req.copy(node = node.name)

                        performanceTracker.recordRequestStart(node.name)

                        val response = webClient.post(node.port, node.host, "/chat")
                            .putHeader("Content-Type", "application/json")
                            .putHeader("X-Request-ID", requestId)
                            .sendBuffer(Buffer.buffer(JsonObject.mapFrom(requestWithNode).encode()))
                            .coAwait()

                        if (response.statusCode() != 200) {
                            loadBalancer.recordFailure(node.name)
                            throw RuntimeException("Node ${node.name} returned status ${response.statusCode()}")
                        }

                        val responseJson = response.bodyAsJsonObject()
                        val usage = responseJson.getJsonObject("usage") ?: JsonObject()
                        val promptTokens = usage.getInteger("prompt_tokens", 0)
                        val completionTokens = usage.getInteger("completion_tokens", 0)

                        val endTime = System.currentTimeMillis()
                        val processingTime = endTime - startTime
                        loadBalancer.recordSuccess(node.name, processingTime)

                        performanceTracker.recordRequest(
                            nodeName = node.name,
                            modelId = req.model,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            processingTimeMs = processingTime
                        )

                        logService.log("info", "Chat request completed", mapOf(
                            "nodeName" to node.name,
                            "requestId" to requestId,
                            "processingTimeMs" to processingTime,
                            "promptTokens" to promptTokens,
                            "completionTokens" to completionTokens
                        ))

                        response.bodyAsJsonObject()
                    }.coAwait()

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(result.encode())

                } catch (e: Exception) {
                    logger.error(e) { "Failed to process chat request (requestId: $requestId)" }
                    logService.logError("Failed to process chat request", e, mapOf(
                        "requestId" to requestId,
                        "modelId" to req.model
                    ))
                    respondWithError(ctx, 500, e.message ?: "Unknown error")
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse request (requestId: $requestId)" }
            launch {
                logService.logError("Failed to parse chat request", e, mapOf("requestId" to requestId))
            }
            respondWithError(ctx, 400, "Invalid request: ${e.message}")
        }
    }

    // Add this method to your ChatHandler class
    fun handleStream(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        try {
            val req = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)

            if (req.model.isBlank()) {
                respondWithError(ctx, 400, "Model is required")
                return
            }

            if (req.messages.isEmpty()) {
                respondWithError(ctx, 400, "At least one message is required")
                return
            }

            logger.info {
                "Stream chat request received: model=${req.model}, " +
                        "messagesCount=${req.messages.size} (requestId: $requestId)"
            }

            // Set appropriate headers for streaming
            ctx.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("X-Accel-Buffering", "no")
                .setChunked(true)

            launch {
                logService.log("info", "Stream chat request received", mapOf(
                    "model" to req.model,
                    "messagesCount" to req.messages.size,
                    "requestId" to requestId
                ))

                try {
                    val startTime = System.currentTimeMillis()

                    if (!modelRegistry.isModelAvailable(req.model)) {
                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("Model ${req.model} is not available on any node")).encode()}\n")
                        ctx.response().end()
                        return@launch
                    }

                    val nodeName = nodeService.getBestNodeForModel(req.model)

                    if (nodeName == null) {
                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("No suitable node found for model ${req.model}")).encode()}\n")
                        ctx.response().end()
                        return@launch
                    }

                    val node = nodes.find { it.name == nodeName }
                        ?: throw IllegalStateException("Node $nodeName not found in configuration")

                    logger.info {
                        "Selected node ${node.name} for stream chat request with model ${req.model} " +
                                "(requestId: $requestId)"
                    }

                    logService.log("info", "Selected node for stream chat request", mapOf(
                        "modelId" to req.model,
                        "nodeName" to node.name,
                        "requestId" to requestId
                    ))

                    // We'll create a modified request with the node name added
                    val requestWithNode = req.copy(node = node.name, stream = true)

                    performanceTracker.recordRequestStart(node.name)

                    try {
                        // Make a streaming request to the node
                        val httpRequest = webClient.post(node.port, node.host, "/chat/stream")
                            .putHeader("Content-Type", "application/json")
                            .putHeader("X-Request-ID", requestId)
                            .sendBuffer(Buffer.buffer(JsonObject.mapFrom(requestWithNode).encode()))
                            .coAwait()

                        // If the response wasn't successful, handle the error
                        if (httpRequest.statusCode() != 200) {
                            loadBalancer.recordFailure(node.name)
                            ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("Node ${node.name} returned status ${httpRequest.statusCode()}")).encode()}\n")
                            ctx.response().end()
                            return@launch
                        }

                        // Forward the streaming response directly
                        val responseBuffer = httpRequest.body()

                        // Write the response to our client
                        ctx.response().write(responseBuffer)
                        ctx.response().end()

                        // Record performance metrics
                        val endTime = System.currentTimeMillis()
                        val processingTime = endTime - startTime
                        loadBalancer.recordSuccess(node.name, processingTime)

                        // Approximate metrics based on the response
                        val textResponse = responseBuffer.toString()
                        val approximateTokenCount = textResponse.split(Regex("\\s+")).size

                        performanceTracker.recordRequest(
                            nodeName = node.name,
                            modelId = req.model,
                            promptTokens = req.messages.sumOf { it.content.split(Regex("\\s+")).size },
                            completionTokens = approximateTokenCount,
                            processingTimeMs = processingTime
                        )

                        logService.log("info", "Stream chat request completed", mapOf(
                            "nodeName" to node.name,
                            "requestId" to requestId,
                            "processingTimeMs" to processingTime,
                            "approximateTokenCount" to approximateTokenCount
                        ))
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process stream chat request (requestId: $requestId)" }
                        loadBalancer.recordFailure(node.name)

                        // Try to write an error response
                        try {
                            ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>(e.message ?: "Unknown error")).encode()}\n")
                            ctx.response().end()
                        } catch (writeError: Exception) {
                            logger.error(writeError) { "Failed to write error response (requestId: $requestId)" }
                        }

                        logService.logError("Failed to process stream chat request", e, mapOf(
                            "requestId" to requestId,
                            "modelId" to req.model,
                            "nodeName" to node.name
                        ))
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process stream chat request (requestId: $requestId)" }

                    // Try to write an error response
                    try {
                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>(e.message ?: "Unknown error")).encode()}\n")
                        ctx.response().end()
                    } catch (writeError: Exception) {
                        logger.error(writeError) { "Failed to write error response (requestId: $requestId)" }
                    }

                    logService.logError("Failed to process stream chat request", e, mapOf(
                        "requestId" to requestId,
                        "modelId" to req.model
                    ))
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse request (requestId: $requestId)" }
            launch {
                logService.logError("Failed to parse stream chat request", e, mapOf("requestId" to requestId))
            }
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
