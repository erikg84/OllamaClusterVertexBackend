package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.Node
import com.dallaslabs.models.VisionRequest
import com.dallaslabs.services.*
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.multipart.MultipartForm
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
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
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Vision analysis request received (requestId: $requestId)" }

        // Process form data and file upload
        ctx.request().setExpectMultipart(true)

        val model = ctx.request().getFormAttribute("model") ?: "llava:13b"
        val prompt = ctx.request().getFormAttribute("prompt") ?: "Describe this image in detail"
        val node = ctx.request().getFormAttribute("node") ?: "unknown"

        var imageFile: File? = null

        ctx.request().uploadHandler { upload ->
            if (upload.name() == "image") {
                val filename = "${UUID.randomUUID()}_${upload.filename()}"
                val filePath = Paths.get(uploadsDir, filename).toString()
                imageFile = File(filePath)

                upload.streamToFileSystem(filePath).onComplete { ar ->
                    if (ar.succeeded()) {
                        logger.info { "File uploaded successfully: $filePath (requestId: $requestId)" }

                        val visionRequest = VisionRequest(model, prompt, node)
                        processVisionRequest(ctx, visionRequest, imageFile!!, requestId)
                    } else {
                        logger.error(ar.cause()) { "File upload failed (requestId: $requestId)" }
                        respondWithError(ctx, 500, "File upload failed: ${ar.cause().message}")
                    }
                }
            }
        }

        // Handle case where no file is uploaded
        ctx.request().endHandler {
            if (imageFile == null) {
                logger.error { "No image file provided (requestId: $requestId)" }
                respondWithError(ctx, 400, "No image file provided")
            }
        }
    }

    fun handleStream(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Vision stream request received (requestId: $requestId)" }

        // Process form data and file upload
        ctx.request().setExpectMultipart(true)

        val model = ctx.request().getFormAttribute("model") ?: "llava:13b"
        val prompt = ctx.request().getFormAttribute("prompt") ?: "Describe this image in detail"
        val node = ctx.request().getFormAttribute("node") ?: "unknown"

        var imageFile: File? = null

        ctx.request().uploadHandler { upload ->
            if (upload.name() == "image") {
                val filename = "${UUID.randomUUID()}_${upload.filename()}"
                val filePath = Paths.get(uploadsDir, filename).toString()
                imageFile = File(filePath)

                upload.streamToFileSystem(filePath).onComplete { ar ->
                    if (ar.succeeded()) {
                        logger.info { "File uploaded successfully: $filePath (requestId: $requestId)" }

                        val visionRequest = VisionRequest(model, prompt, node, stream = true)
                        processVisionStreamRequest(ctx, visionRequest, imageFile!!, requestId)
                    } else {
                        logger.error(ar.cause()) { "File upload failed (requestId: $requestId)" }
                        respondWithError(ctx, 500, "File upload failed: ${ar.cause().message}")
                    }
                }
            }
        }

        // Handle case where no file is uploaded
        ctx.request().endHandler {
            if (imageFile == null) {
                logger.error { "No image file provided (requestId: $requestId)" }
                respondWithError(ctx, 400, "No image file provided")
            }
        }
    }

    private fun processVisionRequest(ctx: RoutingContext, req: VisionRequest, imageFile: File, requestId: String) {
        launch {
            logService.log("info", "Vision analysis request received", mapOf(
                "model" to req.model,
                "prompt" to req.prompt,
                "imageSize" to imageFile.length(),
                "requestId" to requestId
            ))

            try {
                if (!modelRegistry.isModelAvailable(req.model)) {
                    cleanupFile(imageFile)
                    throw IllegalArgumentException("Model ${req.model} is not available on any node")
                }

                val nodeName = nodeService.getBestNodeForModel(req.model)

                if (nodeName == null) {
                    cleanupFile(imageFile)
                    throw IllegalStateException("No suitable node found for model ${req.model}")
                }

                val node = nodes.find { it.name == nodeName }
                    ?: throw IllegalStateException("Node $nodeName not found in configuration")

                logger.info {
                    "Selected node ${node.name} for vision request with model ${req.model} " +
                            "(requestId: $requestId)"
                }

                logService.log("info", "Selected node for vision request", mapOf(
                    "modelId" to req.model,
                    "nodeName" to node.name,
                    "requestId" to requestId
                ))

                val startTime = System.currentTimeMillis()
                performanceTracker.recordRequestStart(node.name)

                val result = queue.add<JsonObject> {
                    try {
                        // Read the file content
                        val fileBytes = Files.readAllBytes(imageFile.toPath())
                        val fileBuffer = Buffer.buffer(fileBytes)
                        val contentType = determineContentType(imageFile.name)

                        // Create a multipart form with the image file and parameters
                        val form = MultipartForm.create()
                            .binaryFileUpload("image", imageFile.name, fileBuffer, contentType)
                            .attribute("model", req.model)
                            .attribute("prompt", req.prompt)
                            .attribute("node", node.name)

                        // Send the request to the node
                        val response = webClient.post(node.port, node.host, "/vision")
                            .putHeader("X-Request-ID", requestId)
                            .sendMultipartForm(form)
                            .coAwait()

                        // Clean up the file after request
                        cleanupFile(imageFile)

                        if (response.statusCode() != 200) {
                            loadBalancer.recordFailure(node.name)
                            throw RuntimeException("Node ${node.name} returned status ${response.statusCode()}")
                        }

                        val responseJson = response.bodyAsJsonObject()

                        // Extract usage metrics for enhanced logging
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

                        logService.log("info", "Vision request completed", mapOf(
                            "nodeName" to node.name,
                            "requestId" to requestId,
                            "processingTimeMs" to processingTime,
                            "promptTokens" to promptTokens,
                            "completionTokens" to completionTokens
                        ))

                        responseJson
                    } catch (e: Exception) {
                        // Make sure we clean up the file even on errors
                        cleanupFile(imageFile)
                        throw e
                    }
                }.coAwait()

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(result.encode())

            } catch (e: Exception) {
                logger.error(e) { "Failed to process vision request (requestId: $requestId)" }
                logService.logError("Failed to process vision request", e, mapOf(
                    "requestId" to requestId,
                    "modelId" to req.model
                ))

                // Make sure we clean up the file on errors
                cleanupFile(imageFile)

                respondWithError(ctx, 500, e.message ?: "Unknown error")
            }
        }
    }

    private fun processVisionStreamRequest(ctx: RoutingContext, req: VisionRequest, imageFile: File, requestId: String) {
        launch {
            logService.log("info", "Vision stream request received", mapOf(
                "model" to req.model,
                "prompt" to req.prompt,
                "imageSize" to imageFile.length(),
                "requestId" to requestId
            ))

            // Set headers for streaming
            ctx.response()
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("X-Accel-Buffering", "no")
                .setChunked(true)

            try {
                if (!modelRegistry.isModelAvailable(req.model)) {
                    cleanupFile(imageFile)
                    ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("Model ${req.model} is not available on any node")).encode()}\n")
                    ctx.response().end()
                    return@launch
                }

                val nodeName = nodeService.getBestNodeForModel(req.model)

                if (nodeName == null) {
                    cleanupFile(imageFile)
                    ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("No suitable node found for model ${req.model}")).encode()}\n")
                    ctx.response().end()
                    return@launch
                }

                val node = nodes.find { it.name == nodeName }
                    ?: throw IllegalStateException("Node $nodeName not found in configuration")

                logger.info {
                    "Selected node ${node.name} for vision stream request with model ${req.model} " +
                            "(requestId: $requestId)"
                }

                logService.log("info", "Selected node for vision stream request", mapOf(
                    "modelId" to req.model,
                    "nodeName" to node.name,
                    "requestId" to requestId
                ))

                val startTime = System.currentTimeMillis()
                performanceTracker.recordRequestStart(node.name)

                try {
                    // Read the file content
                    val fileBytes = Files.readAllBytes(imageFile.toPath())
                    val fileBuffer = Buffer.buffer(fileBytes)
                    val contentType = determineContentType(imageFile.name)

                    // Create a multipart form with the image file and parameters
                    val form = MultipartForm.create()
                        .binaryFileUpload("image", imageFile.name, fileBuffer, contentType)
                        .attribute("model", req.model)
                        .attribute("prompt", req.prompt)
                        .attribute("node", node.name)

                    // Send the request to the node for streaming
                    val streamRequest = webClient.post(node.port, node.host, "/vision/stream")
                        .putHeader("X-Request-ID", requestId)
                        .sendMultipartForm(form)
                        .coAwait()

                    // Clean up the file after request
                    cleanupFile(imageFile)

                    // Process the response as a stream
                    if (streamRequest.statusCode() == 200) {
                        // Forward the streaming response to the client
                        val responseBody = streamRequest.body()
                        ctx.response().write(responseBody)
                        ctx.response().end()

                        // Record success metrics
                        val endTime = System.currentTimeMillis()
                        val processingTime = endTime - startTime
                        loadBalancer.recordSuccess(node.name, processingTime)

                        // For streaming responses, we estimate token counts
                        val responseText = responseBody.toString()
                        val estimatedCompletionTokens = responseText.length / 4

                        performanceTracker.recordRequest(
                            nodeName = node.name,
                            modelId = req.model,
                            promptTokens = req.prompt.length / 4, // Rough estimate
                            completionTokens = estimatedCompletionTokens,
                            processingTimeMs = processingTime
                        )

                        logService.log("info", "Vision stream request completed", mapOf(
                            "nodeName" to node.name,
                            "requestId" to requestId,
                            "processingTimeMs" to processingTime,
                            "estimatedTokens" to estimatedCompletionTokens
                        ))
                    } else {
                        // Handle error response
                        loadBalancer.recordFailure(node.name)
                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>("Node ${node.name} returned status ${streamRequest.statusCode()}")).encode()}\n")
                        ctx.response().end()

                        logService.logError("Vision stream request failed", RuntimeException("Node ${node.name} returned status ${streamRequest.statusCode()}"), mapOf(
                            "requestId" to requestId,
                            "modelId" to req.model,
                            "nodeName" to node.name,
                            "statusCode" to streamRequest.statusCode()
                        ))
                    }
                } catch (e: Exception) {
                    // Make sure we clean up the file even on errors
                    cleanupFile(imageFile)

                    logger.error(e) { "Failed to process vision stream request (requestId: $requestId)" }
                    loadBalancer.recordFailure(node.name)

                    // Try to write an error response
                    try {
                        ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>(e.message ?: "Unknown error")).encode()}\n")
                        ctx.response().end()
                    } catch (writeError: Exception) {
                        logger.error(writeError) { "Failed to write error response (requestId: $requestId)" }
                    }

                    logService.logError("Failed to process vision stream request", e, mapOf(
                        "requestId" to requestId,
                        "modelId" to req.model,
                        "nodeName" to node.name
                    ))
                }
            } catch (e: Exception) {
                // Make sure we clean up the file on errors
                cleanupFile(imageFile)

                logger.error(e) { "Failed to process vision stream request (requestId: $requestId)" }

                // Try to write an error response
                try {
                    ctx.response().write("${JsonObject.mapFrom(ApiResponse.error<Nothing>(e.message ?: "Unknown error")).encode()}\n")
                    ctx.response().end()
                } catch (writeError: Exception) {
                    logger.error(writeError) { "Failed to write error response (requestId: $requestId)" }
                }

                logService.logError("Failed to process vision stream request", e, mapOf(
                    "requestId" to requestId,
                    "modelId" to req.model
                ))
            }
        }
    }

    private fun determineContentType(filename: String): String {
        return when {
            filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
            filename.endsWith(".png", true) -> "image/png"
            filename.endsWith(".gif", true) -> "image/gif"
            filename.endsWith(".webp", true) -> "image/webp"
            filename.endsWith(".bmp", true) -> "image/bmp"
            filename.endsWith(".svg", true) -> "image/svg+xml"
            filename.endsWith(".tiff", true) || filename.endsWith(".tif", true) -> "image/tiff"
            else -> "application/octet-stream"
        }
    }

    private fun cleanupFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete temporary file: ${file.absolutePath}" }
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
