package com.dallaslabs.services

import com.dallaslabs.models.Node
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Service that manages specialized agent roles for complex task orchestration
 */
class AgentService(
    private val vertx: Vertx,
    private val modelRegistry: ModelRegistryService,
    private val loadBalancer: LoadBalancerService,
    private val taskDecompositionService: TaskDecompositionService,
    private val performanceTracker: PerformanceTrackerService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    // Store active agent conversations
    private val activeConversations = ConcurrentHashMap<String, AgentConversation>()

    // Different specialized agent implementations
    private val coordinatorAgent = CoordinatorAgent(vertx, loadBalancer, modelRegistry, logService)
    private val researchAgent = ResearchAgent(vertx, loadBalancer, modelRegistry, logService)
    private val reasoningAgent = ReasoningAgent(vertx, loadBalancer, modelRegistry, logService)
    private val creativeAgent = CreativeAgent(vertx, loadBalancer, modelRegistry, logService)
    private val codeAgent = CodeAgent(vertx, loadBalancer, modelRegistry, logService)
    private val criticAgent = CriticAgent(vertx, loadBalancer, modelRegistry, logService)

    /**
     * Creates a new agent conversation for complex task handling
     * @param initialQuery The initial user query
     * @param preferredModel Optional preferred model to use (if available)
     * @return The conversation ID for tracking
     */
    suspend fun createConversation(initialQuery: String, preferredModel: String? = null): String {
        val conversationId = UUID.randomUUID().toString()

        logger.info { "Creating new agent conversation: $conversationId" }
        logService.log("info", "Creating agent conversation", mapOf(
            "conversationId" to conversationId,
            "queryLength" to initialQuery.length
        ))

        // Create a new conversation with initial state
        val conversation = AgentConversation(
            id = conversationId,
            initialQuery = initialQuery,
            preferredModel = preferredModel,
            messages = mutableListOf(
                AgentMessage(
                    role = "user",
                    content = initialQuery,
                    timestamp = System.currentTimeMillis()
                )
            ),
            state = AgentConversationState.PENDING,
            createdAt = System.currentTimeMillis()
        )

        activeConversations[conversationId] = conversation

        // Start the conversation processing
        vertx.executeBlocking<Unit>({ promise ->
            try {
                // Launch conversation in a non-blocking manner
                launch {
                    processConversation(conversationId)
                }
                promise.complete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to start conversation processing: $conversationId" }
                promise.fail(e)
            }
        }, { /* Ignore result */ })

        return conversationId
    }

    /**
     * Gets the current state of an agent conversation
     * @param conversationId The conversation ID
     * @return The conversation state or null if not found
     */
    fun getConversation(conversationId: String): AgentConversation? {
        return activeConversations[conversationId]
    }

    /**
     * Adds a new user message to an existing conversation
     * @param conversationId The conversation ID
     * @param message The user message
     */
    suspend fun addUserMessage(conversationId: String, message: String): Boolean {
        val conversation = activeConversations[conversationId]
            ?: return false

        logger.info { "Adding user message to conversation: $conversationId" }

        val agentMessage = AgentMessage(
            role = "user",
            content = message,
            timestamp = System.currentTimeMillis()
        )

        conversation.messages.add(agentMessage)

        // If conversation was completed, reset to continue
        if (conversation.state == AgentConversationState.COMPLETED) {
            conversation.state = AgentConversationState.PROCESSING

            // Start the conversation processing
            vertx.executeBlocking<Unit>({ promise ->
                try {
                    // Launch conversation in a non-blocking manner
                    launch {
                        processConversation(conversationId)
                    }
                    promise.complete()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to restart conversation processing: $conversationId" }
                    promise.fail(e)
                }
            }, { /* Ignore result */ })
        }

        return true
    }

    /**
     * Main processing function for agent conversations
     */
    private suspend fun processConversation(conversationId: String) {
        val conversation = activeConversations[conversationId] ?: return

        try {
            // Update state to processing
            conversation.state = AgentConversationState.PROCESSING

            // Step 1: Have coordinator analyze the query
            val analysis = coordinatorAgent.analyzeQuery(
                query = conversation.initialQuery,
                conversationHistory = conversation.messages
            )

            // Add analysis to conversation
            conversation.messages.add(
                AgentMessage(
                    role = "coordinator",
                    content = "Query Analysis: ${analysis.summary}",
                    metadata = JsonObject.mapFrom(analysis),
                    timestamp = System.currentTimeMillis()
                )
            )

            // Step 2: Determine which agents to involve
            val agents = determineRequiredAgents(analysis)

            conversation.messages.add(
                AgentMessage(
                    role = "coordinator",
                    content = "Selected agents: ${agents.joinToString()}",
                    timestamp = System.currentTimeMillis()
                )
            )

            // Step 3: Process with specialized agents in appropriate order
            val agentResults = processWithAgents(conversation, agents, analysis)

            // Step 4: Have coordinator synthesize final response
            val finalResponse = coordinatorAgent.synthesizeFinalResponse(
                query = conversation.initialQuery,
                agentResults = agentResults,
                conversationHistory = conversation.messages
            )

            // Add final response to conversation
            conversation.messages.add(
                AgentMessage(
                    role = "assistant",
                    content = finalResponse,
                    timestamp = System.currentTimeMillis()
                )
            )

            // Update state to completed
            conversation.state = AgentConversationState.COMPLETED

            logService.log("info", "Completed agent conversation", mapOf(
                "conversationId" to conversationId,
                "messageCount" to conversation.messages.size,
                "agentsUsed" to agents.joinToString()
            ))

        } catch (e: Exception) {
            logger.error(e) { "Failed to process agent conversation: $conversationId" }
            logService.logError("Failed to process agent conversation", e, mapOf(
                "conversationId" to conversationId
            ))

            // Add error message to conversation
            conversation.messages.add(
                AgentMessage(
                    role = "system",
                    content = "Error processing conversation: ${e.message}",
                    timestamp = System.currentTimeMillis()
                )
            )

            // Update state to error
            conversation.state = AgentConversationState.ERROR
        }
    }

    /**
     * Determines which specialized agents are needed based on query analysis
     */
    private fun determineRequiredAgents(analysis: QueryAnalysis): List<AgentType> {
        val agents = mutableListOf<AgentType>()

        // Coordinator is always included
        agents.add(AgentType.COORDINATOR)

        // Add appropriate specialized agents based on analysis
        if (analysis.requiresResearch) {
            agents.add(AgentType.RESEARCH)
        }

        if (analysis.requiresReasoning) {
            agents.add(AgentType.REASONING)
        }

        if (analysis.requiresCreativity) {
            agents.add(AgentType.CREATIVE)
        }

        if (analysis.requiresCoding) {
            agents.add(AgentType.CODE)
        }

        // Critic is included for complex multi-agent tasks
        if (agents.size > 2) {
            agents.add(AgentType.CRITIC)
        }

        return agents
    }

    /**
     * Processes a conversation with specialized agents
     */
    private suspend fun processWithAgents(
        conversation: AgentConversation,
        agents: List<AgentType>,
        analysis: QueryAnalysis
    ): Map<AgentType, String> {
        val results = mutableMapOf<AgentType, String>()

        // Process research first if needed
        if (agents.contains(AgentType.RESEARCH)) {
            val researchResult = researchAgent.process(
                query = conversation.initialQuery,
                analysisContext = analysis
            )

            results[AgentType.RESEARCH] = researchResult

            conversation.messages.add(
                AgentMessage(
                    role = "research_agent",
                    content = researchResult,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Process reasoning if needed
        if (agents.contains(AgentType.REASONING)) {
            // Provide research result if available
            val reasoningContext = if (results.containsKey(AgentType.RESEARCH)) {
                "Based on the following research:\n${results[AgentType.RESEARCH]}\n\nReason about: ${conversation.initialQuery}"
            } else {
                "Reason about: ${conversation.initialQuery}"
            }

            val reasoningResult = reasoningAgent.process(
                query = reasoningContext,
                analysisContext = analysis
            )

            results[AgentType.REASONING] = reasoningResult

            conversation.messages.add(
                AgentMessage(
                    role = "reasoning_agent",
                    content = reasoningResult,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Process creative if needed
        if (agents.contains(AgentType.CREATIVE)) {
            // Include research and reasoning if available
            val creativeContext = buildContextForAgent(
                conversation.initialQuery,
                results,
                listOf(AgentType.RESEARCH, AgentType.REASONING)
            )

            val creativeResult = creativeAgent.process(
                query = creativeContext,
                analysisContext = analysis
            )

            results[AgentType.CREATIVE] = creativeResult

            conversation.messages.add(
                AgentMessage(
                    role = "creative_agent",
                    content = creativeResult,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Process code if needed
        if (agents.contains(AgentType.CODE)) {
            // Include research and reasoning if available
            val codeContext = buildContextForAgent(
                conversation.initialQuery,
                results,
                listOf(AgentType.RESEARCH, AgentType.REASONING)
            )

            val codeResult = codeAgent.process(
                query = codeContext,
                analysisContext = analysis
            )

            results[AgentType.CODE] = codeResult

            conversation.messages.add(
                AgentMessage(
                    role = "code_agent",
                    content = codeResult,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Process critic if needed
        if (agents.contains(AgentType.CRITIC)) {
            // Provide all previous results for critique
            val criticContext = buildFullContext(conversation.initialQuery, results)

            val criticResult = criticAgent.process(
                query = criticContext,
                analysisContext = analysis
            )

            results[AgentType.CRITIC] = criticResult

            conversation.messages.add(
                AgentMessage(
                    role = "critic_agent",
                    content = criticResult,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        return results
    }

    /**
     * Builds context for an agent based on previous results
     */
    private fun buildContextForAgent(
        query: String,
        results: Map<AgentType, String>,
        relevantAgents: List<AgentType>
    ): String {
        val context = StringBuilder()

        for (agentType in relevantAgents) {
            if (results.containsKey(agentType)) {
                context.append("${agentType.name} result:\n")
                context.append(results[agentType])
                context.append("\n\n")
            }
        }

        context.append("Based on the above, please address: $query")
        return context.toString()
    }

    /**
     * Builds a full context with all agent results
     */
    private fun buildFullContext(query: String, results: Map<AgentType, String>): String {
        val context = StringBuilder("Original query: $query\n\n")

        for ((agentType, result) in results) {
            context.append("${agentType.name} result:\n")
            context.append(result)
            context.append("\n\n")
        }

        context.append("Please critique the above responses and identify any issues, inconsistencies, or areas for improvement.")
        return context.toString()
    }

    /**
     * Cleans up old conversations
     */
    fun cleanupOldConversations(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val currentTime = System.currentTimeMillis()
        val expiredConversations = activeConversations.entries
            .filter { (_, conversation) ->
                (currentTime - conversation.createdAt) > maxAgeMs
            }
            .map { it.key }

        for (conversationId in expiredConversations) {
            activeConversations.remove(conversationId)
            launch { logService.log("info", "Removed expired conversation", mapOf("conversationId" to conversationId)) }
        }

        logger.info { "Cleaned up ${expiredConversations.size} expired conversations" }
    }
}

/**
 * Represents a conversation between the user and agents
 */
data class AgentConversation(
    val id: String,
    val initialQuery: String,
    val preferredModel: String? = null,
    val messages: MutableList<AgentMessage>,
    var state: AgentConversationState,
    val createdAt: Long,
    var completedAt: Long? = null
)

/**
 * Represents a message in an agent conversation
 */
data class AgentMessage(
    val role: String,             // user, assistant, coordinator, research_agent, etc.
    val content: String,
    val timestamp: Long,
    val metadata: JsonObject? = null
)

/**
 * State of an agent conversation
 */
enum class AgentConversationState {
    PENDING,
    PROCESSING,
    COMPLETED,
    ERROR
}

/**
 * Types of specialized agents
 */
enum class AgentType {
    COORDINATOR,
    RESEARCH,
    REASONING,
    CREATIVE,
    CODE,
    CRITIC
}

/**
 * Analysis of a user query
 */
data class QueryAnalysis(
    val summary: String,
    val complexity: Int,                  // 1-10 scale
    val requiresResearch: Boolean,
    val requiresReasoning: Boolean,
    val requiresCreativity: Boolean,
    val requiresCoding: Boolean,
    val topics: List<String>,
    val suggestedAgents: List<AgentType>,
    val confidenceScore: Double           // 0.0-1.0 scale
)