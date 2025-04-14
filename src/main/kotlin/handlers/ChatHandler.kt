package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.ChatRequest
import com.dallaslabs.models.Node
import com.dallaslabs.services.*
import com.dallaslabs.tracking.FlowTracker
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

        // Start tracking the request flow
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/chat",
            "method" to "POST"
        ))

        try {
            val req = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)

            if (req.model.isBlank()) {
                FlowTracker.recordError(requestId, "validation_error", "Model is required")
                respondWithError(ctx, 400, "Model is required")
                return
            }

            if (req.messages.isEmpty()) {
                FlowTracker.recordError(requestId, "validation_error", "At least one message is required")
                respondWithError(ctx, 400, "At least one message is required")
                return
            }

            if (req.stream) {
                FlowTracker.recordError(requestId, "validation_error", "Streaming is not supported in this endpoint")
                respondWithError(ctx, 400, "Streaming is not supported in this endpoint")
                return
            }

            logger.info {
                "Chat request received: model=${req.model}, " +
                        "messagesCount=${req.messages.size}, stream=${req.stream} (requestId: $requestId)"
            }

            // Update flow state to ANALYZING
            FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                "model" to req.model,
                "messagesCount" to req.messages.size,
                "charCount" to req.messages.sumOf { it.content.length }
            ))

            launch {
                logService.log("info", "Chat request received", mapOf(
                    "model" to req.model,
                    "messagesCount" to req.messages.size,
                    "stream" to req.stream,
                    "requestId" to requestId
                ))

                try {
                    val startTime = System.currentTimeMillis()

                    // Update flow state to QUEUED
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.QUEUED, mapOf(
                        "queueSize" to queue.size(),
                        "queuedAt" to System.currentTimeMillis()
                    ))

                    val result = queue.add<JsonObject> {
                        // Check model availability
                        if (!modelRegistry.isModelAvailable(req.model)) {
                            FlowTracker.recordError(requestId, "model_not_available",
                                "Model ${req.model} is not available on any node")
                            throw IllegalArgumentException("Model ${req.model} is not available on any node")
                        }

                        // Update state to MODEL_SELECTION
                        FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                            "model" to req.model,
                            "isAvailable" to true
                        ))

                        // Select best node for the model
                        val nodeName = nodeService.getBestNodeForModel(req.model)

                        if (nodeName == null) {
                            FlowTracker.recordError(requestId, "no_suitable_node",
                                "No suitable node found for model ${req.model}")
                            throw IllegalStateException("No suitable node found for model ${req.model}")
                        }

                        val node = nodes.find { it.name == nodeName }
                            ?: throw IllegalStateException("Node $nodeName not found in configuration")

                        // Update state to NODE_SELECTION
                        FlowTracker.updateState(requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
                            "selectedNode" to nodeName,
                            "nodeType" to node.type,
                            "nodePlatform" to node.platform
                        ))

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

                        // Update state to EXECUTING
                        FlowTracker.updateState(requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                            "executionStartedAt" to System.currentTimeMillis(),
                            "nodeHost" to node.host,
                            "nodePort" to node.port
                        ))

                        performanceTracker.recordRequestStart(node.name)

                        val response = webClient.post(node.port, node.host, "/chat")
                            .putHeader("Content-Type", "application/json")
                            .putHeader("X-Request-ID", requestId)
                            .sendBuffer(Buffer.buffer(JsonObject.mapFrom(requestWithNode).encode()))
                            .coAwait()

                        if (response.statusCode() != 200) {
                            loadBalancer.recordFailure(node.name)
                            FlowTracker.recordError(requestId, "node_execution_failed",
                                "Node ${node.name} returned status ${response.statusCode()}")
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

                        // Update flow metrics
                        FlowTracker.recordMetrics(requestId, mapOf(
                            "promptTokens" to promptTokens,
                            "completionTokens" to completionTokens,
                            "totalTokens" to (promptTokens + completionTokens),
                            "processingTimeMs" to processingTime,
                            "tokensPerSecond" to if (processingTime > 0) {
                                completionTokens.toDouble() / (processingTime / 1000.0)
                            } else 0.0
                        ))

                        logService.log("info", "Chat request completed", mapOf(
                            "nodeName" to node.name,
                            "requestId" to requestId,
                            "processingTimeMs" to processingTime,
                            "promptTokens" to promptTokens,
                            "completionTokens" to completionTokens
                        ))

                        response.bodyAsJsonObject()
                    }.coAwait()

                    // Update state to COMPLETED
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                        "responseTime" to (System.currentTimeMillis() - startTime),
                        "statusCode" to 200
                    ))

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(result.encode())

                } catch (e: Exception) {
                    // Record error and update state to FAILED
                    FlowTracker.recordError(requestId, "processing_error", e.message ?: "Unknown error")

                    logger.error(e) { "Failed to process chat request (requestId: $requestId)" }
                    logService.logError("Failed to process chat request", e, mapOf(
                        "requestId" to requestId,
                        "modelId" to req.model
                    ))
                    respondWithError(ctx, 500, e.message ?: "Unknown error")
                }
            }

        } catch (e: Exception) {
            // Record error for JSON parsing/validation failures
            FlowTracker.recordError(requestId, "request_parsing_error", e.message ?: "Invalid request format")

            logger.error(e) { "Failed to parse request (requestId: $requestId)" }
            launch {
                logService.logError("Failed to parse chat request", e, mapOf("requestId" to requestId))
            }
            respondWithError(ctx, 400, "Invalid request: ${e.message}")
        }
    }

    fun handleStream(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        // Start tracking the streaming request flow
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/chat/stream",
            "method" to "POST",
            "isStreaming" to true
        ))

        try {
            val req = ctx.body().asJsonObject().mapTo(ChatRequest::class.java)

            if (req.model.isBlank()) {
                FlowTracker.recordError(requestId, "validation_error", "Model is required")
                respondWithError(ctx, 400, "Model is required")
                return
            }

            if (req.messages.isEmpty()) {
                FlowTracker.recordError(requestId, "validation_error", "At least one message is required")
                respondWithError(ctx, 400, "At least one message is required")
                return
            }

            // Update flow state to ANALYZING
            FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                "model" to req.model,
                "messagesCount" to req.messages.size,
                "charCount" to req.messages.sumOf { it.content.length },
                "isStreaming" to true
            ))

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

                    // Check model availability
                    if (!modelRegistry.isModelAvailable(req.model)) {
                        FlowTracker.recordError(requestId, "model_not_available",
                            "Model ${req.model} is not available on any node")

                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("Model ${req.model} is not available on any node")).encode()}\n")
                        ctx.response().end()
                        return@launch
                    }

                    // Update state to MODEL_SELECTION
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                        "model" to req.model,
                        "isAvailable" to true
                    ))

                    // Select best node for the model
                    val nodeName = nodeService.getBestNodeForModel(req.model)

                    if (nodeName == null) {
                        FlowTracker.recordError(requestId, "no_suitable_node",
                            "No suitable node found for model ${req.model}")

                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("No suitable node found for model ${req.model}")).encode()}\n")
                        ctx.response().end()
                        return@launch
                    }

                    val node = nodes.find { it.name == nodeName }
                        ?: throw IllegalStateException("Node $nodeName not found in configuration")

                    // Update state to NODE_SELECTION
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
                        "selectedNode" to nodeName,
                        "nodeType" to node.type,
                        "nodePlatform" to node.platform
                    ))

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

                    // Update state to EXECUTING
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                        "executionStartedAt" to System.currentTimeMillis(),
                        "nodeHost" to node.host,
                        "nodePort" to node.port,
                        "streaming" to true
                    ))

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

                            FlowTracker.recordError(requestId, "node_execution_failed",
                                "Node ${node.name} returned status ${httpRequest.statusCode()}")

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
                        val promptTokenCount = req.messages.sumOf { it.content.split(Regex("\\s+")).size }

                        performanceTracker.recordRequest(
                            nodeName = node.name,
                            modelId = req.model,
                            promptTokens = promptTokenCount,
                            completionTokens = approximateTokenCount,
                            processingTimeMs = processingTime
                        )

                        // Update flow metrics and state to COMPLETED
                        FlowTracker.recordMetrics(requestId, mapOf(
                            "promptTokens" to promptTokenCount,
                            "completionTokens" to approximateTokenCount,
                            "totalTokens" to (promptTokenCount + approximateTokenCount),
                            "processingTimeMs" to processingTime,
                            "tokensPerSecond" to if (processingTime > 0) {
                                approximateTokenCount.toDouble() / (processingTime / 1000.0)
                            } else 0.0,
                            "responseSize" to responseBuffer.length()
                        ))

                        FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                            "responseTime" to processingTime,
                            "statusCode" to 200,
                            "streaming" to true
                        ))

                        logService.log("info", "Stream chat request completed", mapOf(
                            "nodeName" to node.name,
                            "requestId" to requestId,
                            "processingTimeMs" to processingTime,
                            "approximateTokenCount" to approximateTokenCount
                        ))
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process stream chat request (requestId: $requestId)" }
                        loadBalancer.recordFailure(node.name)

                        // Record error in FlowTracker
                        FlowTracker.recordError(requestId, "streaming_error", e.message ?: "Unknown streaming error")

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

                    // Record error in FlowTracker
                    FlowTracker.recordError(requestId, "processing_error", e.message ?: "Unknown error")

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
            // Record error for JSON parsing/validation failures
            FlowTracker.recordError(requestId, "request_parsing_error", e.message ?: "Invalid request format")

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