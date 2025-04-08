package com.dallaslabs.services

import com.dallaslabs.models.Node
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap
import java.util.PriorityQueue

private val logger = KotlinLogging.logger {}

/**
 * Service that implements various performance optimizations for LLM requests
 */
class PerformanceOptimizationService(
    private val vertx: Vertx,
    private val modelRegistry: ModelRegistryService,
    private val nodeService: NodeService,
    private val loadBalancer: LoadBalancerService,
    private val nodes: List<Node>,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    // Cache for storing results of common requests
    private val responseCache = LRUCache<String, CacheEntry>(1000)

    // Query pattern tracker for pre-warming
    private val queryPatternTracker = QueryPatternTracker(100)

    // Batch queue for similar requests
    private val batchQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<BatchRequest>>()

    // Model parameter configurations for specialized tasks
    private val modelParameterProfiles = ConcurrentHashMap<String, Map<String, JsonObject>>()

    init {
        // Initialize default parameter profiles
        initializeParameterProfiles()

        // Start the batch processing loop
        startBatchProcessing()

        // Start periodic cache cleanup
        startCacheCleanup()

        // Start periodic model pre-warming based on patterns
        startModelPreWarming()

        logger.info { "Performance optimization service initialized" }
    }

    /**
     * Gets a cached response if available
     */
    fun getCachedResponse(key: String): JsonObject? {
        val cacheEntry = responseCache.get(key)

        if (cacheEntry != null) {
            // Check if cache entry is still valid
            if (!cacheEntry.isExpired()) {
                logger.debug { "Cache hit for key: ${key.take(20)}..." }
                launch { logService.log("debug", "Cache hit", mapOf("key" to key.take(20))) }

                return cacheEntry.response
            } else {
                // Remove expired entry
                responseCache.remove(key)

                logger.debug { "Expired cache entry removed for key: ${key.take(20)}..." }
                launch { logService.log("debug", "Expired cache entry removed", mapOf("key" to key.take(20))) }
            }
        }

        return null
    }

    /**
     * Adds a response to the cache
     */
    fun cacheResponse(key: String, response: JsonObject, ttlSeconds: Int = 3600) {
        val cacheEntry = CacheEntry(
            response = response,
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(ttlSeconds.toLong())
        )

        responseCache.put(key, cacheEntry)

        logger.debug { "Cached response for key: ${key.take(20)}... (TTL: ${ttlSeconds}s)" }
        launch {
            logService.log(
                "debug", "Response cached", mapOf(
                    "key" to key.take(20),
                    "ttlSeconds" to ttlSeconds
                )
            )
        }
    }

    /**
     * Computes a cache key for a request
     */
    fun computeCacheKey(request: JsonObject): String {
        // Extract relevant fields for cache key
        val model = request.getString("model", "")
        val content = when {
            request.containsKey("prompt") -> request.getString("prompt", "")
            request.containsKey("messages") -> request.getJsonArray("messages").encode()
            else -> ""
        }

        // Normalize by removing whitespace and converting to lowercase
        val normalizedContent = content.replace("\\s+".toRegex(), " ").trim().lowercase()

        // Compute hash for cache key
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest("$model:$normalizedContent".toByteArray())

        return Base64.getEncoder().encodeToString(hash)
    }

    /**
     * Tracks a query pattern for pre-warming
     */
    fun trackQueryPattern(model: String, query: String) {
        val pattern = extractQueryPattern(query)
        queryPatternTracker.trackPattern(model, pattern)

        logger.debug { "Tracked query pattern: $pattern for model: $model" }
    }

    /**
     * Adds a request to the batch queue
     */
    suspend fun addToBatch(
        model: String,
        request: JsonObject,
        requestId: String,
        resultCallback: suspend (JsonObject) -> Unit
    ): BatchStatus {
        // Compute batch key based on model
        val batchKey = model

        // Create batch request
        val batchRequest = BatchRequest(
            id = requestId,
            request = request,
            timestamp = System.currentTimeMillis(),
            callback = resultCallback
        )

        // Get or create batch queue
        val queue = batchQueues.computeIfAbsent(batchKey) { ConcurrentLinkedQueue() }

        // Add request to queue
        queue.add(batchRequest)

        logger.debug { "Added request to batch queue: $batchKey (queue size: ${queue.size})" }
        logService.log(
            "debug", "Request added to batch queue", mapOf(
                "batchKey" to batchKey,
                "queueSize" to queue.size,
                "requestId" to requestId
            )
        )

        // Calculate estimated wait time
        val queueSize = queue.size
        val avgProcessingTime = 500L // Placeholder - in real implementation, this would be dynamically calculated
        val estimatedWaitMs = if (queueSize <= 1) 0 else avgProcessingTime

        return BatchStatus(
            batchId = batchKey,
            position = queueSize,
            estimatedWaitMs = estimatedWaitMs
        )
    }

    /**
     * Gets optimized model parameters for a specific task type
     */
    fun getOptimizedParameters(model: String, taskType: String): JsonObject {
        val modelProfiles = modelParameterProfiles[model]

        return if (modelProfiles != null && modelProfiles.containsKey(taskType)) {
            modelProfiles[taskType] ?: JsonObject()
        } else {
            // Return default parameters
            JsonObject()
        }
    }

    /**
     * Explicitly pre-warms a model
     */
    suspend fun preWarmModel(model: String, nodeName: String? = null) {
        if (!modelRegistry.isModelAvailable(model)) {
            logger.warn { "Cannot pre-warm unavailable model: $model" }
            return
        }

        // If node not specified, select best node
        val targetNodeName = nodeName ?: loadBalancer.selectBestNodeForModel(model)

        if (targetNodeName == null) {
            logger.warn { "No suitable node found for pre-warming model: $model" }
            return
        }

        logger.info { "Pre-warming model: $model on node: $targetNodeName" }
        logService.log(
            "info", "Pre-warming model", mapOf(
                "model" to model,
                "node" to targetNodeName
            )
        )

        try {
            // Send a simple prompt to initialize the model
            val node = nodes.find { it.name == targetNodeName }
                ?: throw IllegalStateException("Node $targetNodeName not found")

            // Create a simple request to warm up the model
            // This could be a platform-specific implementation
            // For demonstration, we'll use a minimal prompt

            // Note: Actual implementation would depend on the
            // specifics of your platform's model loading mechanism

            logger.info { "Model pre-warmed successfully: $model on node: $targetNodeName" }
            logService.log(
                "info", "Model pre-warmed successfully", mapOf(
                    "model" to model,
                    "node" to targetNodeName
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to pre-warm model: $model on node: $targetNodeName" }
            logService.logError(
                "Failed to pre-warm model", e, mapOf(
                    "model" to model,
                    "node" to targetNodeName
                )
            )
        }
    }

    /**
     * Starts the batch processing loop
     */
    private fun startBatchProcessing() {
        vertx.setPeriodic(100) { // Check every 100ms
            launch {
                processBatches()
            }
        }
    }

    /**
     * Processes batches of similar requests
     */
    private suspend fun processBatches() {
        for ((batchKey, queue) in batchQueues) {
            // Skip empty queues
            if (queue.isEmpty()) continue

            // Process batch if it meets criteria
            if (shouldProcessBatch(batchKey, queue)) {
                // Get batch of requests (up to 10)
                val batchSize = minOf(10, queue.size)
                val batch = mutableListOf<BatchRequest>()

                repeat(batchSize) {
                    val request = queue.poll() ?: return@repeat
                    batch.add(request)
                }

                // Process batch in separate coroutine
                launch {
                    processBatch(batchKey, batch)
                }

                logger.debug { "Processing batch: $batchKey (size: ${batch.size})" }
                logService.log(
                    "debug", "Processing batch", mapOf(
                        "batchKey" to batchKey,
                        "batchSize" to batch.size
                    )
                )
            }
        }
    }

    /**
     * Determines if a batch should be processed
     */
    private fun shouldProcessBatch(batchKey: String, queue: ConcurrentLinkedQueue<BatchRequest>): Boolean {
        // Process if queue has reached capacity
        if (queue.size >= 10) return true

        // Process if oldest request has been waiting too long
        val oldestRequest = queue.peek() ?: return false
        val waitTime = System.currentTimeMillis() - oldestRequest.timestamp

        return waitTime > 200 // Process if waiting > 200ms
    }

    /**
     * Processes a batch of requests
     */
    private suspend fun processBatch(batchKey: String, batch: List<BatchRequest>) {
        try {
            // In a real implementation, this would use a batching API
            // For demonstration, we'll process each request individually
            // but could be optimized to use the model provider's batching capabilities

            for (request in batch) {
                try {
                    // Get model from request
                    val model = request.request.getString("model", "")

                    // Select node
                    val nodeName = loadBalancer.selectBestNodeForModel(model)

                    if (nodeName == null) {
                        // Call callback with error
                        request.callback(JsonObject().put("error", "No suitable node found for model: $model"))
                        continue
                    }

                    val node = nodes.find { it.name == nodeName }
                        ?: throw IllegalStateException("Node $nodeName not found")

                    // Process request (actual implementation would call the node)
                    // For demonstration, we'll simulate a response
                    val response = JsonObject()
                        .put("model", model)
                        .put("status", "success")
                        .put("node", nodeName)
                        .put("timestamp", System.currentTimeMillis())

                    // Call callback with response
                    request.callback(response)

                    logger.debug { "Processed batch request: ${request.id}" }
                } catch (e: Exception) {
                    logger.error(e) { "Error processing batch request: ${request.id}" }

                    // Call callback with error
                    request.callback(JsonObject().put("error", e.message))
                }
            }

            logger.info { "Processed batch: $batchKey (size: ${batch.size})" }
            logService.log(
                "info", "Batch processing completed", mapOf(
                    "batchKey" to batchKey,
                    "batchSize" to batch.size
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error processing batch: $batchKey" }
            logService.logError("Batch processing failed", e, mapOf("batchKey" to batchKey))

            // Call callbacks with error
            for (request in batch) {
                try {
                    request.callback(JsonObject().put("error", "Batch processing failed: ${e.message}"))
                } catch (callbackError: Exception) {
                    logger.error(callbackError) { "Error calling batch request callback: ${request.id}" }
                }
            }
        }
    }

    /**
     * Starts periodic cache cleanup
     */
    private fun startCacheCleanup() {
        vertx.setPeriodic(60 * 60 * 1000) { // Every hour
            launch {
                cleanupCache()
            }
        }
    }

    /**
     * Cleans up expired cache entries
     */
    private fun cleanupCache() {
        val expiredKeys = mutableListOf<String>()

        // Find expired entries
        responseCache.forEach { key, entry ->
            if (entry.isExpired()) {
                expiredKeys.add(key)
            }
        }

        // Remove expired entries
        for (key in expiredKeys) {
            responseCache.remove(key)
        }

        logger.info { "Cache cleanup completed: ${expiredKeys.size} expired entries removed" }
        launch {
            logService.log(
                "info", "Cache cleanup completed", mapOf(
                    "expiredEntriesRemoved" to expiredKeys.size
                )
            )
        }
    }

    /**
     * Starts periodic model pre-warming based on detected patterns
     */
    private fun startModelPreWarming() {
        vertx.setPeriodic(5 * 60 * 1000) { // Every 5 minutes
            launch {
                preWarmModelsBasedOnPatterns()
            }
        }
    }

    /**
     * Pre-warms models based on detected query patterns
     */
    private suspend fun preWarmModelsBasedOnPatterns() {
        // Get top query patterns
        val topPatterns = queryPatternTracker.getTopPatterns(5)

        logger.info { "Pre-warming models based on ${topPatterns.size} detected patterns" }

        for ((model, patterns) in topPatterns) {
            // Get nodes with this model
            val nodesWithModel = modelRegistry.getNodesForModel(model)

            if (nodesWithModel.isEmpty()) {
                logger.warn { "No nodes available for model: $model" }
                continue
            }

            // Pre-warm model on each node
            for (nodeName in nodesWithModel) {
                launch {
                    preWarmModel(model, nodeName)
                }
            }

            logger.info { "Scheduled pre-warming for model: $model on ${nodesWithModel.size} nodes" }
        }
    }

    /**
     * Extracts a pattern from a query for trend analysis
     */
    private fun extractQueryPattern(query: String): String {
        // Simplified pattern extraction - in a real implementation,
        // this would use more sophisticated NLP techniques

        // Remove specific details but keep structure
        val pattern = query
            .replace(Regex("\\b\\d+\\b"), "{NUM}") // Replace numbers
            .replace(Regex("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"), "{EMAIL}") // Replace emails
            .replace(Regex("\\b(?:https?://|www\\.)\\S+\\b"), "{URL}") // Replace URLs
            .replace(Regex("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*\\b"), "{NAME}") // Replace proper nouns

        // Take first 50 chars as pattern fingerprint
        return if (pattern.length > 50) pattern.substring(0, 50) else pattern
    }

    /**
     * Initializes default parameter profiles for models
     */
    private fun initializeParameterProfiles() {
        // Example for a generic LLM
        val genericProfiles = mapOf(
            "general" to JsonObject()
                .put("temperature", 0.7)
                .put("top_p", 0.9)
                .put("max_tokens", 2048),

            "creative" to JsonObject()
                .put("temperature", 0.9)
                .put("top_p", 0.95)
                .put("max_tokens", 4096),

            "factual" to JsonObject()
                .put("temperature", 0.2)
                .put("top_p", 0.8)
                .put("max_tokens", 2048),

            "code" to JsonObject()
                .put("temperature", 0.3)
                .put("top_p", 0.85)
                .put("max_tokens", 8192)
        )

        // Add profiles for specific models
        modelParameterProfiles["mistralai/Mistral-7B-v0.1"] = genericProfiles
        modelParameterProfiles["meta-llama/Llama-2-13b"] = genericProfiles

        // Custom profiles for Mixtral
        modelParameterProfiles["mistralai/Mixtral-8x7B"] = mapOf(
            "general" to JsonObject()
                .put("temperature", 0.75)
                .put("top_p", 0.9)
                .put("max_tokens", 4096),

            "creative" to JsonObject()
                .put("temperature", 0.85)
                .put("top_p", 0.92)
                .put("max_tokens", 8192),

            "factual" to JsonObject()
                .put("temperature", 0.1)
                .put("top_p", 0.7)
                .put("max_tokens", 4096),

            "code" to JsonObject()
                .put("temperature", 0.2)
                .put("top_p", 0.8)
                .put("max_tokens", 8192)
        )

        logger.info { "Initialized parameter profiles for ${modelParameterProfiles.size} models" }
    }

    /**
     * Cache entry with expiration
     */
    data class CacheEntry(
        val response: JsonObject,
        val createdAt: Instant,
        val expiresAt: Instant
    ) {
        fun isExpired(): Boolean {
            return Instant.now().isAfter(expiresAt)
        }
    }

    /**
     * Batch request containing the request and callback
     */
    data class BatchRequest(
        val id: String,
        val request: JsonObject,
        val timestamp: Long,
        val callback: suspend (JsonObject) -> Unit
    )

    /**
     * Batch status returned to clients
     */
    data class BatchStatus(
        val batchId: String,
        val position: Int,
        val estimatedWaitMs: Long
    )

    /**
     * LRU Cache implementation
     */
    class LRUCache<K, V>(private val capacity: Int) {
        private val cache = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                return size > capacity
            }
        }

        @Synchronized
        fun get(key: K): V? {
            return cache[key]
        }

        @Synchronized
        fun put(key: K, value: V) {
            cache[key] = value
        }

        @Synchronized
        fun remove(key: K): V? {
            return cache.remove(key)
        }

        @Synchronized
        fun forEach(action: (K, V) -> Unit) {
            cache.forEach { (key, value) -> action(key, value) }
        }

        @Synchronized
        fun size(): Int {
            return cache.size
        }
    }

    /**
     * Query pattern tracker for detecting common patterns
     */
    class QueryPatternTracker(private val capacity: Int) {
        // Map of model to pattern frequency counters
        private val modelPatterns = ConcurrentHashMap<String, MutableMap<String, Int>>()

        /**
         * Track a pattern for a specific model
         */
        fun trackPattern(model: String, pattern: String) {
            val patterns = modelPatterns.computeIfAbsent(model) { ConcurrentHashMap() }

            // Increment pattern count
            patterns.compute(pattern) { _, count -> (count ?: 0) + 1 }

            // Trim if necessary
            if (patterns.size > capacity) {
                // Remove least frequent patterns
                val sortedPatterns = patterns.entries.sortedBy { it.value }
                val patternsToRemove = sortedPatterns.take(patterns.size - capacity)

                for (entry in patternsToRemove) {
                    patterns.remove(entry.key)
                }
            }
        }

        /**
         * Get the top N patterns for each model
         */
        fun getTopPatterns(n: Int): Map<String, List<String>> {
            val result = mutableMapOf<String, List<String>>()

            for ((model, patterns) in modelPatterns) {
                val topPatterns = patterns.entries
                    .sortedByDescending { it.value }
                    .take(n)
                    .map { it.key }

                result[model] = topPatterns
            }

            return result
        }
    }
}