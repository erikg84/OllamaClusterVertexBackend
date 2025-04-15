package com.dallaslabs.handlers

import com.dallaslabs.models.Node
import com.dallaslabs.services.*
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.multipart.MultipartForm
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import java.util.UUID

private val logger = KotlinLogging.logger {}

class VisionHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val nodeService: NodeService,
    private val nodes: List<Node>,
    private val performanceTracker: PerformanceTrackerService,
    private val loadBalancer: LoadBalancerService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val webClient = WebClient.create(vertx, WebClientOptions()
        .setConnectTimeout(30000)
        .setIdleTimeout(60000)
    )

    private val uploadsDir = "uploads"

    init {
        // Create uploads directory if it doesn't exist
        val dir = File(uploadsDir)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info { "Created uploads directory: ${dir.absolutePath}" }
        }
    }

    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()
        logger.info { "Vision request received (requestId: $requestId)" }

        val fileUploads = ctx.fileUploads()
        if (fileUploads.isEmpty()) {
            ctx.response().setStatusCode(400).end("No image file provided")
            return
        }

        val imageUpload = fileUploads.first()
        val model = ctx.request().getFormAttribute("model") ?: "llava:13b"
        val prompt = ctx.request().getFormAttribute("prompt") ?: "Describe this image in detail"
        val node = ctx.request().getFormAttribute("node") ?: "local"

        launch {
            try {
                // Log the incoming request
                logService.log("info", "Vision request received", mapOf(
                    "model" to model,
                    "prompt" to prompt,
                    "imageSize" to imageUpload.size(),
                    "requestId" to requestId
                ))

                // Check if model is available
                if (!modelRegistry.isModelAvailable(model)) {
                    throw IllegalArgumentException("Model $model is not available on any node")
                }

                // Get the best node for this model
                val nodeName = nodeService.getBestNodeForModel(model)
                    ?: throw IllegalStateException("No suitable node found for model $model")

                val nodeFromServer = nodes.find { it.name == nodeName }
                    ?: throw IllegalStateException("Node $nodeName not found in configuration")

                logger.info { "Selected node $nodeName for vision request (requestId: $requestId)" }

                // Create the multipart form
                val multipartForm = MultipartForm.create()
                multipartForm.binaryFileUpload(
                    "image",
                    imageUpload.fileName(),
                    imageUpload.uploadedFileName(),
                    imageUpload.contentType()
                )
                multipartForm.attribute("model", model)
                multipartForm.attribute("prompt", prompt)
                multipartForm.attribute("node", nodeName)

                // Start tracking performance
                val startTime = System.currentTimeMillis()
                performanceTracker.recordRequestStart(nodeName)

                // Use the queue for request management and load balancing
                val result = queue.add {
                    try {
                        val response = webClient.post(nodeFromServer.port, nodeFromServer.host, "/vision")
                            .putHeader("X-Request-ID", requestId)
                            .sendMultipartForm(multipartForm)
                            .coAwait()

                        // Record metrics
                        val endTime = System.currentTimeMillis()
                        val processingTime = endTime - startTime

                        if (response.statusCode() == 200) {
                            loadBalancer.recordSuccess(nodeName, processingTime)

                            // Extract usage metrics if available
                            val responseBody = try {
                                response.bodyAsJsonObject()
                            } catch (e: Exception) {
                                logger.warn { "Failed to parse response body as JSON: ${e.message}" }
                                null
                            }
                            val usage = responseBody?.getJsonObject("usage")
                            val promptTokens = usage?.getInteger("prompt_tokens") ?: 0
                            val completionTokens = usage?.getInteger("completion_tokens") ?: 0

                            performanceTracker.recordRequest(
                                nodeName = nodeName,
                                modelId = model,
                                promptTokens = promptTokens,
                                completionTokens = completionTokens,
                                processingTimeMs = processingTime
                            )

                            logService.log("info", "Vision request completed", mapOf(
                                "nodeName" to nodeName,
                                "requestId" to requestId,
                                "processingTimeMs" to processingTime,
                                "promptTokens" to promptTokens,
                                "completionTokens" to completionTokens
                            ))

                            response
                        } else {
                            loadBalancer.recordFailure(nodeName)
                            logService.log("error", "Vision request failed", mapOf(
                                "nodeName" to nodeName,
                                "requestId" to requestId,
                                "statusCode" to response.statusCode(),
                                "errorMessage" to response.bodyAsString()
                            ))
                            response
                        }
                    } catch (e: Exception) {
                        loadBalancer.recordFailure(nodeName)
                        logService.logError("Vision request failed", e, mapOf(
                            "requestId" to requestId,
                            "modelId" to model,
                            "nodeName" to nodeName
                        ))
                        throw e
                    }
                }.coAwait()

                // Clean up the uploaded file
                vertx.fileSystem().delete(imageUpload.uploadedFileName()).await()

                // Return the response
                if (result.statusCode() == 200) {
                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .end(result.body())
                } else {
                    ctx.response()
                        .setStatusCode(result.statusCode())
                        .end("Vision API error: ${result.bodyAsString()}")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to process vision request (requestId: $requestId)" }
                ctx.response().setStatusCode(500).end("Error: ${e.message}")

                try {
                    vertx.fileSystem().delete(imageUpload.uploadedFileName())
                } catch (deleteError: Exception) {
                    logger.error(deleteError) { "Failed to delete temporary file: ${imageUpload.uploadedFileName()}" }
                }
            }
        }
    }

    fun handleStream(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()
        logger.info { "Vision stream request received (requestId: $requestId)" }

        val fileUploads = ctx.fileUploads()
        if (fileUploads.isEmpty()) {
            ctx.response().setStatusCode(400).end("No image file provided")
            return
        }

        val imageUpload = fileUploads.first()
        val model = ctx.request().getFormAttribute("model") ?: "llava:13b"
        val prompt = ctx.request().getFormAttribute("prompt") ?: "Describe this image in detail"
        val node = ctx.request().getFormAttribute("node") ?: "local"

        launch {
            try {
                // Set headers for streaming
                ctx.response()
                    .putHeader("Content-Type", "text/event-stream")
                    .putHeader("Cache-Control", "no-cache")
                    .putHeader("Connection", "keep-alive")
                    .putHeader("X-Accel-Buffering", "no")
                    .setChunked(true)

                val multipartForm = MultipartForm.create()
                multipartForm.binaryFileUpload(
                    "image",
                    imageUpload.fileName(),
                    imageUpload.uploadedFileName(),
                    imageUpload.contentType()
                )
                multipartForm.attribute("model", model)
                multipartForm.attribute("prompt", prompt)
                multipartForm.attribute("node", node)

                val nodeName = nodeService.getBestNodeForModel(model)
                val nodeFromServer = nodes.find { it.name == nodeName }
                    ?: throw IllegalStateException("Node $nodeName not found in configuration")

                // Create a web client that directly streams the response
                val streamClient = webClient.post(nodeFromServer.port, nodeFromServer.host, "/vision/stream")
                    .putHeader("X-Request-ID", requestId)

                // Send the form data
                val request = streamClient.sendMultipartForm(multipartForm)

                // Set up handlers before awaiting response
                request.onComplete { ar ->
                    if (ar.succeeded()) {
                        val response = ar.result()

                        // Forward the streaming response
                        ctx.response().write(response.body())
                        ctx.response().end()
                    } else {
                        ctx.response()
                            .setStatusCode(500)
                            .end("Vision API stream error: ${ar.cause().message}")
                    }

                    // Clean up regardless of outcome
                    try {
                        vertx.fileSystem().delete(imageUpload.uploadedFileName())
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to delete temporary file: ${imageUpload.uploadedFileName()}" }
                    }
                }

            } catch (e: Exception) {
                logger.error(e) { "Failed to process vision stream request (requestId: $requestId)" }
                ctx.response().setStatusCode(500).end("Error: ${e.message}")

                try {
                    vertx.fileSystem().delete(imageUpload.uploadedFileName())
                } catch (deleteError: Exception) {
                    logger.error(deleteError) { "Failed to delete temporary file: ${imageUpload.uploadedFileName()}" }
                }
            }
        }
    }
}
