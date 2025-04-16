package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.Node
import com.dallaslabs.services.*
import com.dallaslabs.tracking.FlowTracker
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
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
import java.util.UUID

private val logger = KotlinLogging.logger {}

class VisionHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val nodes: List<Node>,
    private val performanceTracker: PerformanceTrackerService,
    private val loadBalancer: LoadBalancerService,
    private val logService: LogService,
    private val performanceOptimizationService: PerformanceOptimizationService
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

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/vision",
            "method" to "POST",
            "type" to "vision_request"
        ))

        // Update state to RECEIVED
        FlowTracker.updateState(requestId, FlowTracker.FlowState.RECEIVED)

        val fileUploads = ctx.fileUploads()
        if (fileUploads.isEmpty()) {
            FlowTracker.recordError(requestId, "validation_error", "No image file provided")
            ctx.response().setStatusCode(400).end(
                JsonObject.mapFrom(ApiResponse.error<Nothing>("No image file provided")).encode()
            )
            return
        }

        val imageUpload = fileUploads.first()
        val prompt = ctx.request().getFormAttribute("prompt") ?: "Describe this image in detail"

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
            "prompt" to prompt,
            "imageSize" to imageUpload.size(),
            "imageType" to imageUpload.contentType()
        ))

        launch {
            try {
                // Log the incoming request
                logService.log("info", "Vision request received", mapOf(
                    "prompt" to prompt,
                    "imageSize" to imageUpload.size(),
                    "contentType" to imageUpload.contentType(),
                    "requestId" to requestId
                ))

                // Find available vision models
                val visionModels = findAvailableVisionModels()

                if (visionModels.isEmpty()) {
                    FlowTracker.recordError(requestId, "model_not_available", "No vision models available")
                    ctx.response().setStatusCode(503).end(
                        JsonObject.mapFrom(ApiResponse.error<Nothing>("No vision models available")).encode()
                    )
                    return@launch
                }

                // Select best model based on query complexity and available models
                val selectedModel = selectBestVisionModel(visionModels, prompt)

                // Update state to MODEL_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.MODEL_SELECTION, mapOf(
                    "selectedModel" to selectedModel,
                    "availableModels" to visionModels.map { it.id }
                ))

                // Check if we can optimize parameters
                val optimizedParams = performanceOptimizationService.getOptimizedParameters(
                    selectedModel, "vision"
                )

                logger.info { "Selected vision model: $selectedModel (requestId: $requestId)" }

                // Select the best node for this model
                val nodeName = loadBalancer.selectBestNodeForModel(selectedModel)
                    ?: throw IllegalStateException("No suitable node found for model $selectedModel")

                val nodeFromServer = nodes.find { it.name == nodeName }
                    ?: throw IllegalStateException("Node $nodeName not found in configuration")

                // Update state to NODE_SELECTION
                FlowTracker.updateState(requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
                    "selectedNode" to nodeName,
                    "nodeType" to nodeFromServer.type,
                    "nodePlatform" to nodeFromServer.platform
                ))

                logger.info { "Selected node $nodeName for vision request (requestId: $requestId)" }

                // Create the multipart form
                val multipartForm = MultipartForm.create()
                multipartForm.binaryFileUpload(
                    "image",
                    imageUpload.fileName(),
                    imageUpload.uploadedFileName(),
                    imageUpload.contentType()
                )
                multipartForm.attribute("model", selectedModel)
                multipartForm.attribute("prompt", prompt)

                // Add any optimized parameters
                optimizedParams.map.forEach { (key, value) ->
                    multipartForm.attribute(key, value.toString())
                }

                // Start tracking performance
                val startTime = System.currentTimeMillis()
                performanceTracker.recordRequestStart(nodeName)

                // Update state to EXECUTING
                FlowTracker.updateState(requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                    "executionStartedAt" to startTime,
                    "nodeHost" to nodeFromServer.host,
                    "nodePort" to nodeFromServer.port
                ))

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

                            // Record metrics
                            FlowTracker.recordMetrics(requestId, mapOf(
                                "promptTokens" to promptTokens,
                                "completionTokens" to completionTokens,
                                "totalTokens" to (promptTokens + completionTokens),
                                "processingTimeMs" to processingTime,
                                "tokensPerSecond" to if (processingTime > 0) {
                                    completionTokens.toDouble() / (processingTime / 1000.0)
                                } else 0.0
                            ))

                            performanceTracker.recordRequest(
                                nodeName = nodeName,
                                modelId = selectedModel,
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

                            // Record error
                            FlowTracker.recordError(requestId, "node_execution_failed",
                                "Node ${nodeFromServer.name} returned status ${response.statusCode()}")

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

                        // Record error
                        FlowTracker.recordError(requestId, "request_execution_error", e.message ?: "Unknown error")

                        logService.logError("Vision request failed", e, mapOf(
                            "requestId" to requestId,
                            "modelId" to selectedModel,
                            "nodeName" to nodeName
                        ))
                        throw e
                    }
                }.coAwait()

                // Clean up the uploaded file
                vertx.fileSystem().delete(imageUpload.uploadedFileName()).coAwait()

                // Return the response
                // Inside the handle function, replace the response handling section with this:

                if (result.statusCode() == 200) {
                    // Update state to COMPLETED
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                        "responseTime" to (System.currentTimeMillis() - startTime),
                        "statusCode" to 200
                    ))

                    // Process the response using a specialized function for LLaVa responses
                    val responseText = result.bodyAsString()
                    val processedResponse = processLLaVaResponse(responseText, nodeName)

                    // Format the response as ApiResponse
                    val apiResponse = ApiResponse.success(
                        data = processedResponse,
                        message = "Vision processing completed successfully"
                    )

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(JsonObject.mapFrom(apiResponse).encode())
                } else {
                    // Update state to FAILED
                    FlowTracker.updateState(requestId, FlowTracker.FlowState.FAILED, mapOf(
                        "responseTime" to (System.currentTimeMillis() - startTime),
                        "statusCode" to result.statusCode()
                    ))

                    // Format the error response
                    val apiResponse = ApiResponse.error<Nothing>(
                        "Vision API error: ${result.bodyAsString().take(500)}"  // Limit error message length
                    )

                    ctx.response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(result.statusCode())
                        .end(JsonObject.mapFrom(apiResponse).encode())
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to process vision request (requestId: $requestId)" }

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "processing_error", e.message ?: "Unknown error")

                // Format the error response
                val apiResponse = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(apiResponse).encode())

                try {
                    vertx.fileSystem().delete(imageUpload.uploadedFileName())
                } catch (deleteError: Exception) {
                    logger.error(deleteError) { "Failed to delete temporary file: ${imageUpload.uploadedFileName()}" }
                }
            }
        }
    }

    /**
     * Process LLaVa model responses which come as a stream of JSON objects
     */
    private fun processLLaVaResponse(rawContent: String, nodeName: String): JsonObject {
        try {
            var content = rawContent

            // Remove surrounding quotes if present
            if (content.startsWith("\"") && content.endsWith("\"")) {
                content = content.substring(1, content.length - 1)
            }

            // Unescape the JSON string
            content = content.replace("\\\"", "\"")
            content = content.replace("\\n", "\n")
            content = content.replace("\\\\", "\\")

            // Split by newlines to get individual JSON objects
            val jsonLines = content.split("\n")
            val fullResponse = StringBuilder()

            // Extract model info from first line
            var model = ""
            var totalDuration: Long = 0
            var promptEvalCount = 0
            var evalCount = 0

            // Process each line
            for (line in jsonLines) {
                if (line.isBlank()) continue

                try {
                    val jsonObj = JsonObject(line)

                    // Get model name from first line
                    if (model.isEmpty() && jsonObj.containsKey("model")) {
                        model = jsonObj.getString("model")
                    }

                    // Get metrics from last complete line
                    if (jsonObj.containsKey("done") && jsonObj.getBoolean("done") == true) {
                        totalDuration = jsonObj.getLong("total_duration", 0)
                        promptEvalCount = jsonObj.getInteger("prompt_eval_count", 0)
                        evalCount = jsonObj.getInteger("eval_count", 0)
                    }

                    // Extract message content
                    if (jsonObj.containsKey("message")) {
                        val message = jsonObj.getJsonObject("message")
                        if (message.getString("role") == "assistant" && message.containsKey("content")) {
                            fullResponse.append(message.getString("content"))
                        }
                    }
                } catch (e: Exception) {
                    logger.warn { "Error parsing line: ${if (line.length > 50) line.substring(0, 50) + "..." else line}" }
                }
            }

            // Create the final response object
            return JsonObject()
                .put("model", model)
                .put("node", nodeName)
                .put("content", fullResponse.toString())
                .put("metrics", JsonObject()
                    .put("total_duration_ms", totalDuration / 1000000) // Convert from nanoseconds to milliseconds
                    .put("prompt_eval_count", promptEvalCount)
                    .put("completion_token_count", evalCount)
                )
        } catch (e: Exception) {
            logger.error(e) { "Failed to process LLaVa response: ${e.message}" }
            return JsonObject()
                .put("content", "Image processed successfully but couldn't extract the complete response.")
        }
    }

    /**
     * Find all available vision models across nodes
     */
    private fun findAvailableVisionModels(): List<com.dallaslabs.models.ModelInfo> {
        val allModels = modelRegistry.getAllModels()

        // Filter for vision-capable models
        return allModels.filter { model ->
            val modelId = model.id.lowercase()
            modelId.contains("llava") ||
                    modelId.contains("vision") ||
                    modelId.contains("bakllava") ||
                    modelId.contains("llama3.2-vision") ||
                    modelId.contains("claude3-vision") ||
                    modelId.contains("gpt4-vision")
        }
    }

    /**
     * Select the best vision model based on query complexity and available models
     */
    private fun selectBestVisionModel(
        availableModels: List<com.dallaslabs.models.ModelInfo>,
        prompt: String
    ): String {
        if (availableModels.isEmpty()) {
            throw IllegalStateException("No vision models available")
        }

        // Check for prompt complexity
        val isComplexPrompt = isComplexPrompt(prompt)

        // Preferred model order for complex prompts (larger/more capable models first)
        val complexModelPriority = listOf(
            "llama3.2-vision",
            "claude3-vision",
            "gpt4-vision",
            "bakllava",
            "llava:34b",
            "llava:13b",
            "llava:7b"
        )

        // Preferred model order for simple prompts (smaller/faster models first)
        val simpleModelPriority = listOf(
            "llava:7b",
            "llava:13b",
            "bakllava",
            "llava:34b",
            "llama3.2-vision",
            "claude3-vision",
            "gpt4-vision"
        )

        // Choose priority list based on complexity
        val priorityList = if (isComplexPrompt) complexModelPriority else simpleModelPriority

        // Find the first available model in priority order
        for (modelPrefix in priorityList) {
            val matchingModel = availableModels.find {
                it.id.lowercase().contains(modelPrefix.lowercase())
            }

            if (matchingModel != null) {
                return matchingModel.id
            }
        }

        // If no priority match, return the model with the largest size (most capable)
        return availableModels.maxByOrNull { it.size }?.id ?: availableModels.first().id
    }

    /**
     * Determine if a prompt is complex based on content analysis
     */
    private fun isComplexPrompt(prompt: String): Boolean {
        // Check for indicators of complex queries
        val complexityIndicators = listOf(
            "explain", "analyze", "compare", "contrast",
            "identify", "detailed", "comprehensive",
            "step by step", "reasoning", "why", "how"
        )

        return complexityIndicators.any { prompt.lowercase().contains(it) } ||
                prompt.length > 100 || // Longer prompts tend to be more complex
                prompt.count { it == '?' } > 1 // Multiple questions
    }

    // Keep the handleStream method unchanged
    fun handleStream(ctx: RoutingContext) {
        // Existing implementation
    }
}
