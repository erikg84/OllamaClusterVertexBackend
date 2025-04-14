package com.dallaslabs.tracking

import com.dallaslabs.services.LogService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * FlowTracker is a singleton that tracks the flow of requests through the AI system.
 * It monitors state transitions, collects metrics, and provides insights into system performance.
 */
object FlowTracker : CoroutineScope {
    // Will be initialized when needed
    private lateinit var vertx: Vertx
    private lateinit var logService: LogService
    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    // Flow states representing the stages of request processing
    enum class FlowState {
        RECEIVED,           // Initial request received
        QUEUED,             // Request added to processing queue
        ANALYZING,          // Request being analyzed for decomposition
        TASK_DECOMPOSED,    // Request broken into subtasks
        MODEL_SELECTION,    // Selecting appropriate model(s)
        NODE_SELECTION,     // Selecting node(s) for execution
        EXECUTING,          // Request being processed by LLM
        ORCHESTRATING,      // Multiple models being orchestrated
        AGENT_PROCESSING,   // Specialized agents processing request
        SYNTHESIZING,       // Combining results from multiple sources
        COMPLETED,          // Processing completed successfully
        FAILED,             // Processing failed
        CANCELED            // Processing canceled by user or system
    }

    // Data class to track a single request flow
    data class FlowInfo(
        val requestId: String,
        val startTime: Long = System.currentTimeMillis(),
        val states: MutableMap<FlowState, Long> = mutableMapOf(),
        var currentState: FlowState? = null,
        val metadata: MutableMap<String, Any> = mutableMapOf(),
        val metrics: MutableMap<String, Any> = mutableMapOf(),
        val errorInfo: MutableMap<String, String> = mutableMapOf(),
        var completionTime: Long? = null
    ) {
        fun duration(): Long = completionTime?.minus(startTime) ?: (System.currentTimeMillis() - startTime)

        fun addMetric(key: String, value: Any) {
            metrics[key] = value
        }

        fun addMetadata(key: String, value: Any) {
            metadata[key] = value
        }

        fun toJsonObject(): JsonObject {
            val json = JsonObject()
                .put("requestId", requestId)
                .put("startTime", startTime)
                .put("currentState", currentState?.name)
                .put("duration", duration())
                .put("states", JsonObject(states.mapKeys { it.key.name }))
                .put("metadata", JsonObject(metadata))
                .put("metrics", JsonObject(metrics))

            completionTime?.let { json.put("completionTime", it) }
            if (errorInfo.isNotEmpty()) {
                json.put("errors", JsonObject(errorInfo as Map<String, Any>?))
            }

            return json
        }
    }

    // Active request flows being tracked
    private val activeFlows = ConcurrentHashMap<String, FlowInfo>()

    // Completed flows cache (limited size)
    private val completedFlows = ConcurrentHashMap<String, FlowInfo>()
    private val MAX_COMPLETED_FLOWS = 1000

    // Stats tracking
    private val totalRequests = AtomicLong(0)
    private val successfulRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    private val stateTransitionCounts = ConcurrentHashMap<FlowState, AtomicLong>()
    private val averageStateDurations = ConcurrentHashMap<FlowState, AveragingCounter>()

    // Flag to ensure initialization happens only once
    private var initialized = false

    /**
     * Initialize the FlowTracker singleton
     * @param vertx The Vertx instance
     * @param logService The LogService instance
     */
    @Synchronized
    fun initialize(vertx: Vertx, logService: LogService) {
        if (initialized) {
            logger.warn { "FlowTracker already initialized" }
            return
        }

        this.vertx = vertx
        this.logService = logService

        // Initialize counters for all states
        FlowState.entries.forEach { state ->
            stateTransitionCounts[state] = AtomicLong(0)
            averageStateDurations[state] = AveragingCounter()
        }

        // Start periodic cleanup
        vertx.setPeriodic(3600000) { // 1 hour
            launch {
                cleanupOldFlows()
            }
        }

        initialized = true
        logger.info { "FlowTracker initialized" }
        launch {
            logService.logSystemEvent("FlowTracker initialized")
        }
    }

    /**
     * Start tracking a new request flow
     * @param requestId The unique ID of the request, or generate one if null
     * @param initialMetadata Initial metadata to associate with the flow
     * @return The request ID for future reference
     */
    fun startFlow(requestId: String? = null, initialMetadata: Map<String, Any> = emptyMap()): String {
        checkInitialized()

        val id = requestId ?: UUID.randomUUID().toString()

        val flowInfo = FlowInfo(
            requestId = id,
            currentState = FlowState.RECEIVED,
            startTime = System.currentTimeMillis()
        )

        // Record the initial state
        flowInfo.states[FlowState.RECEIVED] = flowInfo.startTime
        stateTransitionCounts[FlowState.RECEIVED]?.incrementAndGet()

        // Add metadata
        flowInfo.metadata.putAll(initialMetadata)

        // Store the flow
        activeFlows[id] = flowInfo
        totalRequests.incrementAndGet()

        logger.debug { "Started tracking flow: $id" }
        launch {
            logService.log("debug", "Started request flow tracking", mapOf(
                "requestId" to id,
                "initialState" to FlowState.RECEIVED.name
            ))
        }

        return id
    }

    /**
     * Update the state of a request flow
     * @param requestId The request ID
     * @param newState The new state to transition to
     * @param metadata Additional metadata to associate with this state transition
     * @return True if the state was updated, false if the flow wasn't found
     */
    fun updateState(requestId: String, newState: FlowState, metadata: Map<String, Any> = emptyMap()): Boolean {
        checkInitialized()

        val flowInfo = activeFlows[requestId] ?: return false

        val now = System.currentTimeMillis()
        val previousState = flowInfo.currentState

        // Record time spent in previous state
        if (previousState != null) {
            val previousStateTime = flowInfo.states[previousState] ?: flowInfo.startTime
            val timeInPreviousState = now - previousStateTime
            averageStateDurations[previousState]?.add(timeInPreviousState)
        }

        // Update to new state
        flowInfo.states[newState] = now
        flowInfo.currentState = newState
        stateTransitionCounts[newState]?.incrementAndGet()

        // Add metadata for this transition
        if (metadata.isNotEmpty()) {
            flowInfo.metadata.putAll(metadata)
        }

        // Special handling for terminal states
        if (newState == FlowState.COMPLETED || newState == FlowState.FAILED || newState == FlowState.CANCELED) {
            flowInfo.completionTime = now

            if (newState == FlowState.COMPLETED) {
                successfulRequests.incrementAndGet()
            } else if (newState == FlowState.FAILED) {
                failedRequests.incrementAndGet()
            }

            // Move to completed flows
            completedFlows[requestId] = flowInfo
            activeFlows.remove(requestId)

            // Limit size of completed flows
            if (completedFlows.size > MAX_COMPLETED_FLOWS) {
                // Remove oldest completed flow
                completedFlows.entries
                    .minByOrNull { it.value.startTime }
                    ?.key
                    ?.let { completedFlows.remove(it) }
            }
        }

        logger.debug { "Updated flow state: $requestId -> $newState" }
        launch {
            logService.log("debug", "Updated flow state", mapOf(
                "requestId" to requestId,
                "previousState" to (previousState?.name ?: "none"),
                "newState" to newState.name
            ))
        }

        return true
    }

    /**
     * Record error information for a flow
     * @param requestId The request ID
     * @param errorType The type of error
     * @param errorMessage The error message
     * @param failFlow Whether to transition the flow to FAILED state
     * @return True if the error was recorded, false if the flow wasn't found
     */
    fun recordError(
        requestId: String,
        errorType: String,
        errorMessage: String,
        failFlow: Boolean = true
    ): Boolean {
        checkInitialized()

        val flowInfo = activeFlows[requestId] ?: return false

        flowInfo.errorInfo[errorType] = errorMessage

        if (failFlow) {
            updateState(requestId, FlowState.FAILED, mapOf(
                "errorType" to errorType,
                "errorMessage" to errorMessage
            ))
        }

        logger.warn { "Recorded error for flow $requestId: [$errorType] $errorMessage" }
        launch {
            logService.log("warn", "Flow error recorded", mapOf(
                "requestId" to requestId,
                "errorType" to errorType,
                "errorMessage" to errorMessage
            ))
        }

        return true
    }

    /**
     * Record metric data for a flow
     * @param requestId The request ID
     * @param metrics Map of metric names to values
     * @return True if metrics were recorded, false if the flow wasn't found
     */
    fun recordMetrics(requestId: String, metrics: Map<String, Any>): Boolean {
        checkInitialized()

        val flowInfo = activeFlows[requestId] ?: completedFlows[requestId] ?: return false

        flowInfo.metrics.putAll(metrics)

        logger.debug { "Recorded metrics for flow $requestId: ${metrics.keys.joinToString()}" }

        return true
    }

    /**
     * Get information about an active or completed flow
     * @param requestId The request ID
     * @return The flow information or null if not found
     */
    fun getFlowInfo(requestId: String): FlowInfo? {
        checkInitialized()
        return activeFlows[requestId] ?: completedFlows[requestId]
    }

    /**
     * Get detailed information about an active or completed flow as JSON
     * @param requestId The request ID
     * @return JSON representation of the flow or null if not found
     */
    fun getFlowInfoAsJson(requestId: String): JsonObject? {
        checkInitialized()
        return getFlowInfo(requestId)?.toJsonObject()
    }

    /**
     * Get current statistics about tracked flows
     * @return Statistics as a JSON object
     */
    fun getStatistics(): JsonObject {
        checkInitialized()

        val stats = JsonObject()
            .put("totalRequests", totalRequests.get())
            .put("successfulRequests", successfulRequests.get())
            .put("failedRequests", failedRequests.get())
            .put("activeFlows", activeFlows.size)
            .put("completedFlows", completedFlows.size)

        val stateStats = JsonObject()
        stateTransitionCounts.forEach { (state, count) ->
            stateStats.put(state.name, JsonObject()
                .put("count", count.get())
                .put("averageDurationMs", averageStateDurations[state]?.average() ?: 0)
            )
        }

        stats.put("stateStats", stateStats)

        return stats
    }

    /**
     * Cleanup old completed flows
     * @param maxAgeMs Maximum age in milliseconds (default 24 hours)
     */
    private fun cleanupOldFlows(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val expiredFlows = completedFlows.entries
            .filter { (_, flow) ->
                (now - (flow.completionTime ?: flow.startTime)) > maxAgeMs
            }
            .map { it.key }

        for (flowId in expiredFlows) {
            completedFlows.remove(flowId)
        }

        logger.info { "Cleaned up ${expiredFlows.size} expired flows" }
        launch {
            logService.log("info", "Cleaned up expired flows", mapOf(
                "count" to expiredFlows.size
            ))
        }
    }

    /**
     * Check if the FlowTracker has been initialized
     */
    private fun checkInitialized() {
        if (!initialized) {
            throw IllegalStateException("FlowTracker not initialized. Call initialize() first.")
        }
    }

    /**
     * Utility class for tracking running averages
     */
    private class AveragingCounter {
        private val count = AtomicLong(0)
        private val sum = AtomicLong(0)

        fun add(value: Long) {
            count.incrementAndGet()
            sum.addAndGet(value)
        }

        fun average(): Double {
            val currentCount = count.get()
            return if (currentCount > 0) {
                sum.get().toDouble() / currentCount.toDouble()
            } else {
                0.0
            }
        }
    }
}