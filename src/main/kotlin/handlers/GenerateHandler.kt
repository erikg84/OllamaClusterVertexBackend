package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.GenerateRequest
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
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

class GenerateHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val loadBalancer: LoadBalancerService,
    private val nodes: List<Node>,
    private val performanceTracker: PerformanceTrackerService,
    private val logService: LogService,
    private val taskDecompositionService: TaskDecompositionService,
    private val performanceOptimizationService: PerformanceOptimizationService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val webClient = WebClient.create(
        vertx, WebClientOptions()
            .setConnectTimeout(30000)
            .setIdleTimeout(60000)
    )

    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"

        try {
            val req = ctx.body().asJsonObject().mapTo(GenerateRequest::class.java)

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

            launch {
                logService.log("info", "Generate request received", mapOf(
                    "model" to req.model,
                    "promptLength" to req.prompt.length,
                    "stream" to req.stream,
                    "requestId" to requestId
                ))

                try {
                    // Track query pattern for pre-warming
                    performanceOptimizationService.trackQueryPattern(req.model, req.prompt)

                    // Compute cache key
                    val cacheKey = performanceOptimizationService.computeCacheKey(
                        JsonObject.mapFrom(req)
                    )

                    // Check cache first
                    val cachedResponse = performanceOptimizationService.getCachedResponse(cacheKey)
                    if (cachedResponse != null) {
                        logger.info { "Returning cached response for request (requestId: $requestId)" }
                        logService.log("info", "Using cached response", mapOf(
                            "requestId" to requestId,
                            "cacheKey" to cacheKey
                        ))

                        ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .setStatusCode(200)
                            .end(cachedResponse.encode())
                        return@launch
                    }

                    // If request should be batched and is not time-sensitive
                    if (shouldBatchRequest(req)) {
                        logger.info { "Adding request to batch queue (requestId: $requestId)" }

                        val batchStatus = performanceOptimizationService.addToBatch(
                            model = req.model,
                            request = JsonObject.mapFrom(req),
                            requestId = requestId
                        ) { batchResult ->
                            // Cache the result for future use
                            performanceOptimizationService.cacheResponse(cacheKey, batchResult)

                            // Send the result to the client
                            ctx.response()
                                .putHeader("Content-Type", "application/json")
                                .setStatusCode(200)
                                .end(batchResult.encode())
                        }

                        logService.log("info", "Request added to batch queue", mapOf(
                            "requestId" to requestId,
                            "model" to req.model,
                            "batchId" to batchStatus.batchId,
                            "position" to batchStatus.position,
                            "estimatedWaitMs" to batchStatus.estimatedWaitMs
                        ))

                        return@launch
                    }

                    // Check if request should be decomposed
                    val isComplexRequest = shouldDecomposeRequest(req.prompt)

                    if (isComplexRequest) {
                        // Process using task decomposition
                        val result = processDecomposedRequest(ctx, req, requestId)
                        // Cache decomposed result
                        performanceOptimizationService.cacheResponse(cacheKey, result)
                    } else {
                        // Process normally with optimized parameters
                        processStandardRequestWithOptimization(ctx, req, cacheKey, requestId)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process generation request (requestId: $requestId)" }
                    logService.logError("Failed to process generation request", e, mapOf(
                        "requestId" to requestId,
                        "modelId" to req.model
                    ))
                    respondWithError(ctx, 500, e.message ?: "Unknown error")
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse request (requestId: $requestId)" }
            launch {
                logService.logError("Failed to parse generation request", e, mapOf("requestId" to requestId))
            }
            respondWithError(ctx, 400, "Invalid request: ${e.message}")
        }
    }

    private fun shouldBatchRequest(req: GenerateRequest): Boolean {
        // Don't batch requests that:
        // - Are explicitly marked as high priority
        // - Have very short prompts (likely quick responses)
        // - Use specialized models that aren't good for batching

        if (req.options?.getBoolean("high_priority", false) == true) {
            return false
        }

        if (req.prompt.length < 50) {
            return false
        }

        // Avoid batching certain models that aren't good candidates
        val noBatchModels = listOf("stablelm", "mpt")
        if (noBatchModels.any { req.model.contains(it, ignoreCase = true) }) {
            return false
        }

        // Batch if load is high
        return queue.size() > 5
    }

    private fun shouldDecomposeRequest(prompt: String): Boolean {
        // Simple heuristic to decide if we should decompose the request
        return prompt.length > 500 ||
                prompt.contains("?") && prompt.count { it == '?' } > 1 ||
                prompt.contains("step") && prompt.contains("by") && prompt.contains("step") ||
                prompt.contains(Regex("\\d+\\.\\s+\\w+")) || // Numbered list
                prompt.contains(Regex("\\*\\s+\\w+"))        // Bulleted list
    }

    private suspend fun processDecomposedRequest(ctx: RoutingContext, req: GenerateRequest, requestId: String): JsonObject = coroutineScope {
        val startTime = System.currentTimeMillis()
        val workflow = taskDecompositionService.decomposeRequest(req)
        logService.log(
            "info", "Request decomposed for processing", mapOf(
                "requestId" to requestId,
                "workflowId" to workflow.id,
                "taskCount" to workflow.tasks.size,
                "strategy" to workflow.decompositionStrategy.name
            )
        )
        while (!taskDecompositionService.isWorkflowComplete(workflow.id)) {
            val executableTasks = taskDecompositionService.getNextExecutableTasks(workflow.id)

            if (executableTasks.isEmpty()) {
                // No tasks ready to execute, wait for in-progress tasks
                delay(100)
                continue
            }

            // Execute all ready tasks in parallel
            val taskDeferreds = executableTasks.map { task ->
                async {
                    try {
                        taskDecompositionService.updateTaskStatus(workflow.id, task.id, TaskStatus.RUNNING)

                        val result = queue.add<JsonObject> {
                            executeTask(task, requestId)
                        }.coAwait()

                        taskDecompositionService.updateTaskStatus(
                            workflow.id,
                            task.id,
                            TaskStatus.COMPLETED,
                            result
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Task execution failed: ${task.id}" }
                        taskDecompositionService.updateTaskStatus(
                            workflow.id,
                            task.id,
                            TaskStatus.FAILED
                        )
                    }
                }
            }

            // Wait for all current tasks to complete
            taskDeferreds.awaitAll()
        }
        val finalResult = taskDecompositionService.getFinalResult(workflow.id)
            ?: throw IllegalStateException("Workflow complete but no final result available")

        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime
        logService.log(
            "info", "Decomposed request completed", mapOf(
                "requestId" to requestId,
                "workflowId" to workflow.id,
                "processingTimeMs" to processingTime
            )
        )
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(finalResult.encode())

        finalResult
    }

    private suspend fun executeTask(task: Task, requestId: String): JsonObject {
        val taskRequest = task.originalRequest as GenerateRequest
        val taskStartTime = System.currentTimeMillis()

        // Get optimized parameters based on task type
        val optimizedParams = performanceOptimizationService.getOptimizedParameters(
            taskRequest.model,
            task.type.name.lowercase()
        )

        // Apply optimized parameters to task request
        val enhancedRequest = applyOptimizedParameters(taskRequest, optimizedParams)

        // Select appropriate model based on task type
        val modelToUse = selectModelForTaskType(enhancedRequest.model, task.type)
        val modifiedRequest = enhancedRequest.copy(model = modelToUse)

        // Select node based on task needs
        val nodeName = if (task.type == TaskType.REASONING || task.type == TaskType.SYNTHESIS) {
            // Use higher-quality models for reasoning and synthesis
            loadBalancer.selectBestNodeForModel(modelToUse, "gpu")
        } else {
            loadBalancer.selectBestNodeForModel(modelToUse)
        }

        if (nodeName == null) {
            throw IllegalStateException("No suitable node found for model ${modifiedRequest.model}")
        }

        val node = nodes.find { it.name == nodeName }
            ?: throw IllegalStateException("Node $nodeName not found in configuration")

        logger.info {
            "Selected node ${node.name} for task ${task.id} with model ${modifiedRequest.model}"
        }

        logService.log("info", "Selected node for task", mapOf(
            "taskId" to task.id,
            "type" to task.type.name,
            "nodeName" to node.name,
            "modelId" to modifiedRequest.model,
            "requestId" to requestId
        ))

        val requestWithNode = modifiedRequest.copy(node = node.name)

        performanceTracker.recordRequestStart(node.name)

        try {
            val response = webClient.post(node.port, node.host, "/generate")
                .putHeader("Content-Type", "application/json")
                .putHeader("X-Request-ID", requestId)
                .sendBuffer(Buffer.buffer(JsonObject.mapFrom(requestWithNode).encode()))
                .coAwait()

            if (response.statusCode() != 200) {
                loadBalancer.recordFailure(node.name)
                throw RuntimeException("Node ${node.name} returned status ${response.statusCode()}")
            }

            val endTime = System.currentTimeMillis()
            val processingTime = endTime - taskStartTime
            loadBalancer.recordSuccess(node.name, processingTime)

            val responseJson = response.bodyAsJsonObject()
            val promptTokens = responseJson.getInteger("prompt_eval_count", 0)
            val completionTokens = responseJson.getInteger("eval_count", 0)

            performanceTracker.recordRequest(
                nodeName = node.name,
                modelId = modifiedRequest.model,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                processingTimeMs = processingTime
            )

            logService.log("info", "Task completed", mapOf(
                "taskId" to task.id,
                "nodeName" to node.name,
                "requestId" to requestId,
                "processingTimeMs" to processingTime,
                "promptTokens" to promptTokens,
                "completionTokens" to completionTokens
            ))

            return responseJson
        } catch (e: Exception) {
            loadBalancer.recordFailure(node.name)
            logService.logError("Task execution failed", e, mapOf(
                "taskId" to task.id,
                "nodeName" to node.name,
                "requestId" to requestId
            ))
            throw e
        }
    }

    private fun selectModelForTaskType(defaultModel: String, taskType: TaskType): String {
        // Select appropriate model based on task type
        return when (taskType) {
            TaskType.REASONING, TaskType.SYNTHESIS -> {
                // Use larger models for reasoning and synthesis if available
                if (defaultModel.contains("7b")) {
                    val largerModel = defaultModel.replace("7b", "13b")
                    if (modelRegistry.isModelAvailable(largerModel)) {
                        largerModel
                    } else {
                        defaultModel
                    }
                } else {
                    defaultModel
                }
            }
            TaskType.CREATIVE -> {
                // Perhaps use models fine-tuned for creative tasks
                defaultModel
            }
            TaskType.CODE -> {
                // Use code-specific models if available
                if (modelRegistry.isModelAvailable("codellama")) {
                    "codellama"
                } else {
                    defaultModel
                }
            }
            else -> defaultModel
        }
    }

    private suspend fun processStandardRequestWithOptimization(ctx: RoutingContext, req: GenerateRequest, cacheKey: String, requestId: String) {
        // Get optimized parameters based on content
        val taskType = determineTaskType(req.prompt)
        val optimizedParams = performanceOptimizationService.getOptimizedParameters(req.model, taskType)

        // Apply optimized parameters to request
        val enhancedRequest = applyOptimizedParameters(req, optimizedParams)

        val startTime = System.currentTimeMillis()
        val result = queue.add<JsonObject> {
            if (!modelRegistry.isModelAvailable(enhancedRequest.model)) {
                throw IllegalArgumentException("Model ${enhancedRequest.model} is not available on any node")
            }

            val nodeName = loadBalancer.selectBestNodeForModel(enhancedRequest.model)

            if (nodeName == null) {
                throw IllegalStateException("No suitable node found for model ${enhancedRequest.model}")
            }

            val node = nodes.find { it.name == nodeName }
                ?: throw IllegalStateException("Node $nodeName not found in configuration")

            logger.info {
                "Selected node ${node.name} for generate request with model ${enhancedRequest.model} " +
                        "(requestId: $requestId)"
            }

            logService.log("info", "Selected node for generate request", mapOf(
                "modelId" to enhancedRequest.model,
                "nodeName" to node.name,
                "requestId" to requestId
            ))

            val requestWithNode = enhancedRequest.copy(node = node.name)

            performanceTracker.recordRequestStart(node.name)

            try {
                val response = webClient.post(node.port, node.host, "/generate")
                    .putHeader("Content-Type", "application/json")
                    .putHeader("X-Request-ID", requestId)
                    .sendBuffer(Buffer.buffer(JsonObject.mapFrom(requestWithNode).encode()))
                    .coAwait()

                if (response.statusCode() != 200) {
                    loadBalancer.recordFailure(node.name)
                    throw RuntimeException("Node ${node.name} returned status ${response.statusCode()}")
                }

                val endTime = System.currentTimeMillis()
                val processingTime = endTime - startTime
                loadBalancer.recordSuccess(node.name, processingTime)

                val responseJson = response.bodyAsJsonObject()
                val promptTokens = responseJson.getInteger("prompt_eval_count", 0)
                val completionTokens = responseJson.getInteger("eval_count", 0)

                performanceTracker.recordRequest(
                    nodeName = node.name,
                    modelId = enhancedRequest.model,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    processingTimeMs = processingTime
                )

                logService.log("info", "Request completed", mapOf(
                    "nodeName" to node.name,
                    "requestId" to requestId,
                    "processingTimeMs" to processingTime,
                    "promptTokens" to promptTokens,
                    "completionTokens" to completionTokens
                ))

                response.bodyAsJsonObject()
            } catch (e: Exception) {
                loadBalancer.recordFailure(node.name)
                logService.logError("Generation request failed during forwarding", e, mapOf(
                    "nodeName" to node.name,
                    "requestId" to requestId
                ))
                throw e
            }
        }.coAwait()

        // Cache the result for future use
        performanceOptimizationService.cacheResponse(cacheKey, result)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(200)
            .end(result.encode())
    }

    /**
     * Determines the task type based on prompt content
     */
    private fun determineTaskType(prompt: String): String {
        return when {
            prompt.contains(Regex("code|program|function|class|algorithm", RegexOption.IGNORE_CASE)) -> "code"
            prompt.contains(Regex("write|create|generate|story|poem|novel|essay", RegexOption.IGNORE_CASE)) -> "creative"
            prompt.contains(Regex("fact|information|history|science|explain", RegexOption.IGNORE_CASE)) -> "factual"
            else -> "general"
        }
    }

    /**
     * Applies optimized parameters to a request
     */
    private fun applyOptimizedParameters(req: GenerateRequest, params: JsonObject): GenerateRequest {
        // Don't override explicitly provided parameters
        val options = req.options ?: JsonObject()

        // Apply each parameter only if not already specified
        if (!options.containsKey("temperature") && params.containsKey("temperature")) {
            options.put("temperature", params.getDouble("temperature"))
        }

        if (!options.containsKey("top_p") && params.containsKey("top_p")) {
            options.put("top_p", params.getDouble("top_p"))
        }

        if (!options.containsKey("max_tokens") && params.containsKey("max_tokens")) {
            options.put("max_tokens", params.getInteger("max_tokens"))
        }

        return req.copy(options = options)
    }

    private suspend fun processStandardRequest(ctx: RoutingContext, req: GenerateRequest, requestId: String) {
        // Kept for backward compatibility but now redirects to optimized version
        val cacheKey = performanceOptimizationService.computeCacheKey(JsonObject.mapFrom(req))
        processStandardRequestWithOptimization(ctx, req, cacheKey, requestId)
    }

    private fun respondWithError(ctx: RoutingContext, statusCode: Int, message: String) {
        val response = ApiResponse.error<Nothing>(message)

        ctx.response()
            .putHeader("Content-Type", "application/json")
            .setStatusCode(statusCode)
            .end(JsonObject.mapFrom(response).encode())
    }
}