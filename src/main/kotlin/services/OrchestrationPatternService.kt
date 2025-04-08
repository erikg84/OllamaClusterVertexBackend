package com.dallaslabs.services

import com.dallaslabs.models.Node
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * Service implementing advanced orchestration patterns for LLM coordination
 */
class OrchestrationPatternService(
    private val vertx: Vertx,
    private val modelRegistry: ModelRegistryService,
    private val loadBalancer: LoadBalancerService,
    private val nodeService: NodeService,
    private val nodes: List<Node>,
    private val agentService: AgentService,
    private val taskDecompositionService: TaskDecompositionService,
    private val performanceTracker: PerformanceTrackerService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val webClient = WebClient.create(
        vertx, WebClientOptions()
            .setConnectTimeout(30000)
            .setIdleTimeout(60000)
    )

    // Store ongoing pattern executions
    private val patternExecutions = ConcurrentHashMap<String, PatternExecution>()

    /**
     * Execute a model ensemble pattern where multiple models process the same input
     * and their outputs are combined
     */
    suspend fun executeModelEnsemble(
        query: String,
        models: List<String>? = null,
        ensembleSize: Int = 3,
        consensusThreshold: Double = 0.7,
        requestId: String
    ): ModelEnsembleResult {
        logger.info { "Executing model ensemble for query: ${query.take(50)}..." }

        val executionId = UUID.randomUUID().toString()
        logService.log("info", "Starting model ensemble execution", mapOf(
            "executionId" to executionId,
            "queryLength" to query.length,
            "ensembleSize" to ensembleSize,
            "requestId" to requestId
        ))

        // Determine which models to use
        val availableModels = models?.filter { modelRegistry.isModelAvailable(it) }
            ?: modelRegistry.getAllModels().map { it.id }.distinct().take(ensembleSize)

        if (availableModels.isEmpty()) {
            throw IllegalStateException("No suitable models available for ensemble")
        }

        val execution = PatternExecution(
            id = executionId,
            pattern = OrchestrationPattern.MODEL_ENSEMBLE,
            query = query,
            models = availableModels,
            state = PatternExecutionState.RUNNING,
            startTime = System.currentTimeMillis()
        )

        patternExecutions[executionId] = execution

        // Execute query on all models in parallel
        val modelResults = coroutineScope {
            availableModels.map { model ->
                async {
                    try {
                        val nodeName = loadBalancer.selectBestNodeForModel(model)

                        if (nodeName == null) {
                            logger.warn { "No suitable node found for model $model in ensemble" }
                            null
                        } else {
                            executeModelQuery(model, nodeName, query, requestId)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error executing model $model in ensemble" }
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (modelResults.isEmpty()) {
            execution.state = PatternExecutionState.FAILED
            execution.endTime = System.currentTimeMillis()
            patternExecutions[executionId] = execution

            throw IllegalStateException("All models failed in ensemble execution")
        }

        // Extract completions
        val completions = modelResults.map { result ->
            val text = when {
                result.containsKey("choices") -> {
                    // Format for chat completions
                    val choices = result.getJsonArray("choices")
                    if (choices != null && !choices.isEmpty) {
                        val choice = choices.getJsonObject(0)
                        val message = choice.getJsonObject("message")
                        message?.getString("content") ?: ""
                    } else ""
                }
                result.containsKey("response") -> {
                    // Format for text generation
                    result.getString("response", "")
                }
                else -> ""
            }

            ModelCompletion(
                model = result.getString("model", "unknown"),
                text = text,
                score = 1.0 // Default confidence score
            )
        }

        // Implement ensemble aggregation
        val finalResult = when {
            // Single result case
            completions.size == 1 -> completions.first().text

            // Check for consensus
            hasConsensus(completions, consensusThreshold) -> {
                // Find most common themes/ideas
                findConsensusOutput(completions)
            }

            // Otherwise use weighted combination
            else -> combineOutputs(completions)
        }

        // Update execution state
        execution.state = PatternExecutionState.COMPLETED
        execution.endTime = System.currentTimeMillis()
        execution.result = JsonObject().put("output", finalResult)
        patternExecutions[executionId] = execution

        logService.log("info", "Model ensemble execution completed", mapOf(
            "executionId" to executionId,
            "modelsUsed" to availableModels.size,
            "successfulCompletions" to completions.size,
            "executionTimeMs" to (execution.endTime!! - execution.startTime),
            "requestId" to requestId
        ))

        return ModelEnsembleResult(
            id = executionId,
            completions = completions,
            consensusOutput = finalResult,
            executionTimeMs = (execution.endTime!! - execution.startTime)
        )
    }

    /**
     * Execute a debate pattern where models iteratively refine and critique each other's outputs
     */
    suspend fun executeDebatePattern(
        query: String,
        debateRounds: Int = 3,
        models: List<String>? = null,
        requestId: String
    ): DebateResult {
        logger.info { "Executing debate pattern for query: ${query.take(50)}..." }

        val executionId = UUID.randomUUID().toString()
        logService.log("info", "Starting debate execution", mapOf(
            "executionId" to executionId,
            "queryLength" to query.length,
            "debateRounds" to debateRounds,
            "requestId" to requestId
        ))

        // Determine which models to use
        val availableModels = models?.filter { modelRegistry.isModelAvailable(it) }
            ?: modelRegistry.getAllModels()
                .sortedByDescending { it.size }
                .map { it.id }
                .distinct()
                .take(2) // We need at least 2 models for a debate

        if (availableModels.size < 2) {
            throw IllegalStateException("At least 2 models are required for debate pattern")
        }

        val execution = PatternExecution(
            id = executionId,
            pattern = OrchestrationPattern.DEBATE,
            query = query,
            models = availableModels,
            state = PatternExecutionState.RUNNING,
            startTime = System.currentTimeMillis()
        )

        patternExecutions[executionId] = execution

        // Initialize debate
        val debateMessages = mutableListOf<DebateMessage>()

        // Initial prompt
        val initialPrompt = "Please provide a thoughtful, accurate response to the following query: $query"

        // First response
        val firstModel = availableModels[0]
        val firstNodeName = loadBalancer.selectBestNodeForModel(firstModel)
            ?: throw IllegalStateException("No suitable node found for model $firstModel")

        val firstResponse = executeModelQuery(firstModel, firstNodeName, initialPrompt, requestId)

        // Extract completion
        val firstCompletion = when {
            firstResponse.containsKey("choices") -> {
                // Format for chat completions
                val choices = firstResponse.getJsonArray("choices")
                if (choices != null && !choices.isEmpty) {
                    val choice = choices.getJsonObject(0)
                    val message = choice.getJsonObject("message")
                    message?.getString("content") ?: ""
                } else ""
            }
            firstResponse.containsKey("response") -> {
                // Format for text generation
                firstResponse.getString("response", "")
            }
            else -> ""
        }

        debateMessages.add(
            DebateMessage(
                model = firstModel,
                content = firstCompletion,
                round = 0
            )
        )

        // Execute debate rounds
        var currentRound = 1
        while (currentRound <= debateRounds) {
            // Alternate between models
            val currentModelIndex = currentRound % availableModels.size
            val currentModel = availableModels[currentModelIndex]

            // Previous model's response
            val prevModelResponse = debateMessages.last().content

            // Create debate prompt
            val debatePrompt = buildDebatePrompt(
                query = query,
                previousResponse = prevModelResponse,
                round = currentRound,
                isLastRound = currentRound == debateRounds
            )

            // Execute model query
            val nodeName = loadBalancer.selectBestNodeForModel(currentModel)
                ?: throw IllegalStateException("No suitable node found for model $currentModel")

            val response = executeModelQuery(currentModel, nodeName, debatePrompt, requestId)

            // Extract completion
            val completion = when {
                response.containsKey("choices") -> {
                    // Format for chat completions
                    val choices = response.getJsonArray("choices")
                    if (choices != null && !choices.isEmpty) {
                        val choice = choices.getJsonObject(0)
                        val message = choice.getJsonObject("message")
                        message?.getString("content") ?: ""
                    } else ""
                }
                response.containsKey("response") -> {
                    // Format for text generation
                    response.getString("response", "")
                }
                else -> ""
            }

            debateMessages.add(
                DebateMessage(
                    model = currentModel,
                    content = completion,
                    round = currentRound
                )
            )

            currentRound++
        }

        // Final summary prompt
        val summaryPrompt = "Based on the following debate about the query: $query\n\n" +
                debateMessages.joinToString("\n\n") { message ->
                    "Round ${message.round}, Model ${message.model}:\n${message.content}"
                } +
                "\n\nPlease provide a final synthesized answer that incorporates the best insights from the debate."

        // Use the best model for synthesis
        val bestModel = availableModels.first()
        val bestNodeName = loadBalancer.selectBestNodeForModel(bestModel)
            ?: throw IllegalStateException("No suitable node found for final synthesis")

        val synthesisResponse = executeModelQuery(bestModel, bestNodeName, summaryPrompt, requestId)

        // Extract synthesis
        val synthesis = when {
            synthesisResponse.containsKey("choices") -> {
                // Format for chat completions
                val choices = synthesisResponse.getJsonArray("choices")
                if (choices != null && !choices.isEmpty) {
                    val choice = choices.getJsonObject(0)
                    val message = choice.getJsonObject("message")
                    message?.getString("content") ?: ""
                } else ""
            }
            synthesisResponse.containsKey("response") -> {
                // Format for text generation
                synthesisResponse.getString("response", "")
            }
            else -> ""
        }

        // Update execution state
        execution.state = PatternExecutionState.COMPLETED
        execution.endTime = System.currentTimeMillis()
        execution.result = JsonObject()
            .put("output", synthesis)
            .put("debateRounds", debateMessages.size)

        patternExecutions[executionId] = execution

        logService.log("info", "Debate execution completed", mapOf(
            "executionId" to executionId,
            "rounds" to debateMessages.size,
            "modelsUsed" to availableModels.size,
            "executionTimeMs" to (execution.endTime!! - execution.startTime),
            "requestId" to requestId
        ))

        return DebateResult(
            id = executionId,
            debateMessages = debateMessages,
            finalSynthesis = synthesis,
            executionTimeMs = (execution.endTime!! - execution.startTime)
        )
    }

    /**
     * Execute collaborative agent workflow as described in MAESTRO
     */
    suspend fun executeMAESTROWorkflow(
        query: String,
        preferredModel: String? = null,
        requestId: String
    ): MAESTROResult {
        logger.info { "Executing MAESTRO workflow for query: ${query.take(50)}..." }

        val executionId = UUID.randomUUID().toString()
        logService.log("info", "Starting MAESTRO workflow execution", mapOf(
            "executionId" to executionId,
            "queryLength" to query.length,
            "requestId" to requestId
        ))

        val execution = PatternExecution(
            id = executionId,
            pattern = OrchestrationPattern.MAESTRO,
            query = query,
            models = emptyList(), // Will be populated during execution
            state = PatternExecutionState.RUNNING,
            startTime = System.currentTimeMillis()
        )

        patternExecutions[executionId] = execution

        // Create agent conversation
        val conversationId = agentService.createConversation(query, preferredModel)

        // Wait for conversation to complete
        var conversation = agentService.getConversation(conversationId)

        while (conversation != null &&
            (conversation.state == AgentConversationState.PENDING ||
                    conversation.state == AgentConversationState.PROCESSING)) {
            // Poll for result
            Thread.sleep(500)
            conversation = agentService.getConversation(conversationId)
        }

        if (conversation == null || conversation.state == AgentConversationState.ERROR) {
            execution.state = PatternExecutionState.FAILED
            execution.endTime = System.currentTimeMillis()
            execution.result = JsonObject().put("error", "Agent conversation failed")
            patternExecutions[executionId] = execution

            throw IllegalStateException("MAESTRO workflow failed in agent conversation")
        }

        // Extract final result
        val assistantMessages = conversation.messages.filter { it.role == "assistant" }
        val finalMessage = assistantMessages.lastOrNull()?.content
            ?: throw IllegalStateException("No final assistant message found")

        // Extract models used
        val modelsUsed = conversation.messages
            .filter { it.role != "user" && it.role != "system" }
            .mapNotNull {
                it.metadata?.getString("model")
            }
            .distinct()

        // Update execution state
        execution.state = PatternExecutionState.COMPLETED
        execution.endTime = System.currentTimeMillis()
        execution.models = modelsUsed
        execution.result = JsonObject()
            .put("output", finalMessage)
            .put("conversationId", conversationId)

        patternExecutions[executionId] = execution

        logService.log("info", "MAESTRO workflow execution completed", mapOf(
            "executionId" to executionId,
            "conversationId" to conversationId,
            "modelsUsed" to modelsUsed.size,
            "messagesCount" to conversation.messages.size,
            "executionTimeMs" to (execution.endTime!! - execution.startTime),
            "requestId" to requestId
        ))

        return MAESTROResult(
            id = executionId,
            conversationId = conversationId,
            finalOutput = finalMessage,
            agents = conversation.messages
                .filter { it.role != "user" && it.role != "system" && it.role != "assistant" }
                .map {
                    AgentContribution(
                        agentType = it.role,
                        content = it.content,
                        timestamp = it.timestamp
                    )
                },
            executionTimeMs = (execution.endTime!! - execution.startTime)
        )
    }

    /**
     * Get execution status
     */
    fun getExecutionStatus(executionId: String): PatternExecution? {
        return patternExecutions[executionId]
    }

    /**
     * Clean up old executions
     */
    fun cleanupOldExecutions(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val currentTime = System.currentTimeMillis()
        val expiredExecutions = patternExecutions.entries
            .filter { (_, execution) ->
                execution.endTime != null && (currentTime - (execution.endTime ?: 0L)) > maxAgeMs
            }
            .map { it.key }

        for (executionId in expiredExecutions) {
            patternExecutions.remove(executionId)
            launch { logService.log("info", "Removed expired execution", mapOf("executionId" to executionId)) }
        }

        logger.info { "Cleaned up ${expiredExecutions.size} expired executions" }
    }

    /**
     * Execute a query on a specific model and node
     */
    private suspend fun executeModelQuery(
        model: String,
        nodeName: String,
        query: String,
        requestId: String
    ): JsonObject {
        val node = nodes.find { it.name == nodeName }
            ?: throw IllegalStateException("Node $nodeName not found in configuration")

        // Determine if chat or completion based on model capabilities
        val isChatModel = model.contains("gpt") ||
                model.contains("claude") ||
                model.contains("mistral") ||
                model.contains("llama")

        // Create appropriate request based on model type
        val requestBody = if (isChatModel) {
            JsonObject()
                .put("model", model)
                .put("messages", JsonArray()
                    .add(JsonObject()
                        .put("role", "user")
                        .put("content", query)
                    )
                )
                .put("node", nodeName)
        } else {
            JsonObject()
                .put("model", model)
                .put("prompt", query)
                .put("node", nodeName)
        }

        // Execute request
        val endpoint = if (isChatModel) "/chat" else "/generate"
        performanceTracker.recordRequestStart(nodeName)

        val response = webClient.post(node.port, node.host, endpoint)
            .putHeader("Content-Type", "application/json")
            .putHeader("X-Request-ID", requestId)
            .sendBuffer(Buffer.buffer(requestBody.encode()))
            .coAwait()

        if (response.statusCode() != 200) {
            loadBalancer.recordFailure(nodeName)
            throw RuntimeException("Node $nodeName returned status ${response.statusCode()}")
        }

        val responseJson = response.bodyAsJsonObject()

        // Add model information to response
        responseJson.put("model", model)

        // Track performance
        val processingTime = System.currentTimeMillis() - performanceTracker.getRequestStartTime(nodeName)
        loadBalancer.recordSuccess(nodeName, processingTime)

        return responseJson
    }

    /**
     * Check if completions have consensus
     */
    private fun hasConsensus(completions: List<ModelCompletion>, threshold: Double): Boolean {
        if (completions.isEmpty()) return false
        if (completions.size == 1) return true

        // This is a simplified approach - in a real implementation,
        // you would use semantic similarity or embedding comparison

        // For now, check if completions have similar length and content
        val lengths = completions.map { it.text.length }
        val avgLength = lengths.average()
        val lengthVariance = lengths.map { Math.abs(it - avgLength) / avgLength }.average()

        // If length variance is low, likely similar content
        return lengthVariance < (1.0 - threshold)
    }

    /**
     * Find the consensus output from multiple completions
     */
    private fun findConsensusOutput(completions: List<ModelCompletion>): String {
        if (completions.isEmpty()) return ""
        if (completions.size == 1) return completions.first().text

        // In a real implementation, you would use more sophisticated
        // techniques like clustering based on embeddings

        // For now, pick the completion with the median length
        val sortedByLength = completions.sortedBy { it.text.length }
        return sortedByLength[sortedByLength.size / 2].text
    }

    /**
     * Combine outputs from multiple completions
     */
    private fun combineOutputs(completions: List<ModelCompletion>): String {
        if (completions.isEmpty()) return ""
        if (completions.size == 1) return completions.first().text

        // In a real implementation, you would use more sophisticated
        // techniques like extractive summarization

        // For now, use a template approach
        val result = StringBuilder()

        result.append("Based on multiple model predictions:\n\n")

        completions.forEachIndexed { index, completion ->
            // Extract first paragraph from each completion
            val firstParagraph = completion.text.split("\n\n").firstOrNull() ?: completion.text
            result.append("${index + 1}. Model ${completion.model}: ${firstParagraph.trim()}\n\n")
        }

        result.append("Considering all perspectives, the consensus is that ")

        // Add a synthesized conclusion - in a real implementation this would
        // be generated by another LLM call
        result.append(findConsensusOutput(completions))

        return result.toString()
    }

    /**
     * Build a prompt for the debate pattern
     */
    private fun buildDebatePrompt(
        query: String,
        previousResponse: String,
        round: Int,
        isLastRound: Boolean
    ): String {
        return if (isLastRound) {
            // Final round - synthesize
            """
            I'd like you to provide a final, synthesized response to the following query:
            
            $query
            
            In the previous round, the following response was given:
            
            $previousResponse
            
            This is the final round of our collaborative debate. Please provide a comprehensive response that:
            1. Incorporates the best insights from the previous response
            2. Addresses any gaps or weaknesses in the previous response
            3. Provides a well-rounded, authoritative answer to the original query
            
            Your goal is synthesis and completeness, not critique.
            """.trimIndent()
        } else {
            // Regular round - critique and improve
            """
            I'd like you to contribute to a collaborative debate on the following query:
            
            $query
            
            In the previous round, the following response was given:
            
            $previousResponse
            
            This is round $round of our collaborative debate. Please:
            1. Identify strengths in the previous response
            2. Point out any weaknesses, gaps, factual errors, or questionable reasoning
            3. Provide your own improved response that builds upon the strengths while addressing the weaknesses
            
            Aim to be constructive and collaborative, not adversarial.
            """.trimIndent()
        }
    }
}

/**
 * Orchestration pattern types
 */
enum class OrchestrationPattern {
    MODEL_ENSEMBLE,
    DEBATE,
    MAESTRO
}

/**
 * Pattern execution state
 */
enum class PatternExecutionState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Pattern execution data class
 */
data class PatternExecution(
    val id: String,
    val pattern: OrchestrationPattern,
    val query: String,
    var models: List<String>,
    var state: PatternExecutionState,
    val startTime: Long,
    var endTime: Long? = null,
    var result: JsonObject? = null
)

/**
 * Model completion in an ensemble
 */
data class ModelCompletion(
    val model: String,
    val text: String,
    val score: Double
)

/**
 * Result of a model ensemble execution
 */
data class ModelEnsembleResult(
    val id: String,
    val completions: List<ModelCompletion>,
    val consensusOutput: String,
    val executionTimeMs: Long
)

/**
 * Message in a debate
 */
data class DebateMessage(
    val model: String,
    val content: String,
    val round: Int
)

/**
 * Result of a debate execution
 */
data class DebateResult(
    val id: String,
    val debateMessages: List<DebateMessage>,
    val finalSynthesis: String,
    val executionTimeMs: Long
)

/**
 * Agent contribution in a MAESTRO workflow
 */
data class AgentContribution(
    val agentType: String,
    val content: String,
    val timestamp: Long
)

/**
 * Result of a MAESTRO workflow execution
 */
data class MAESTROResult(
    val id: String,
    val conversationId: String,
    val finalOutput: String,
    val agents: List<AgentContribution>,
    val executionTimeMs: Long
)