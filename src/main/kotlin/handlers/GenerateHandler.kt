package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.GenerateRequest
import com.dallaslabs.models.Node
import com.dallaslabs.services.LoadBalancerService
import com.dallaslabs.services.LogService
import com.dallaslabs.services.ModelRegistryService
import com.dallaslabs.services.PerformanceTrackerService
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
 * Handler for text generation requests
 */
class GenerateHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val loadBalancer: LoadBalancerService,
    private val nodes: List<Node>,
    private val performanceTracker: PerformanceTrackerService,
    logService: LogService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    private val webClient = WebClient.create(
        vertx, WebClientOptions()
            .setConnectTimeout(30000)
            .setIdleTimeout(60000)
    )

    /**
     * Handles generation requests
     */
    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        try {
            val req = ctx.body().asJsonObject().mapTo(GenerateRequest::class.java)

            // Validate request
            if (req.model.isBlank()) {
                respondWithError(ctx, 400, "Model is required")
                return
            }

            if (req.prompt.isBlank()) {
                respondWithError(ctx, 400, "Prompt is required")
                return
            }

            if (req.stream) {
                respondWithError(ctx, 400, "Streaming is not supported yet")
                return
            }

            logger.info {
                "Generate request received: model=${req.model}, " +
                        "promptLength=${req.prompt.length}, stream=${req.stream} (requestId: $requestId)"
            }

            // Process the request
            launch {
                try {
                    val startTime = System.currentTimeMillis()
                    val result = queue.add<JsonObject> {

                        if (!modelRegistry.isModelAvailable(req.model)) {
                            throw IllegalArgumentException("Model ${req.model} is not available on any node")
                        }

                        // Get the best node for this model using the load balancer
                        val nodeName = loadBalancer.selectBestNodeForModel(req.model)

                        if (nodeName == null) {
                            throw IllegalStateException("No suitable node found for model ${req.model}")
                        }

                        val node = nodes.find { it.name == nodeName }
                            ?: throw IllegalStateException("Node $nodeName not found in configuration")

                        logger.info {
                            "Selected node ${node.name} for generate request with model ${req.model} " +
                                    "(requestId: $requestId)"
                        }

                        val requestWithNode = req.copy(node = node.name)

                        performanceTracker.recordRequestStart(node.name)

                        try {
                            // Forward request to the selected node
                            val response = webClient.post(node.port, node.host, "/generate")
                                .putHeader("Content-Type", "application/json")
                                .putHeader("X-Request-ID", requestId)
                                .sendBuffer(Buffer.buffer(JsonObject.mapFrom(requestWithNode).encode()))
                                .coAwait()

                            if (response.statusCode() != 200) {
                                loadBalancer.recordFailure(node.name)
                                throw RuntimeException("Node ${node.name} returned status ${response.statusCode()}")
                            }

                            // Record success
                            val endTime = System.currentTimeMillis()
                            val processingTime = endTime - startTime
                            loadBalancer.recordSuccess(node.name, endTime - startTime)

                            val responseJson = response.bodyAsJsonObject()
                            val promptTokens = responseJson.getInteger("prompt_eval_count", 0)
                            val completionTokens = responseJson.getInteger("eval_count", 0)

                            // Record performance metrics
                            performanceTracker.recordRequest(
                                nodeName = node.name,
                                modelId = req.model,
                                promptTokens = promptTokens,
                                completionTokens = completionTokens,
                                processingTimeMs = processingTime
                            )

                            response.bodyAsJsonObject()
                        } catch (e: Exception) {
                            // Record failure
                            loadBalancer.recordFailure(node.name)
                            throw e
                        }
                    }.coAwait()

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(result.encode())

                } catch (e: Exception) {
                    logger.error(e) { "Failed to process generation request (requestId: $requestId)" }
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
