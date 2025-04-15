package com.dallaslabs.services

import com.dallaslabs.models.Log
import com.dallaslabs.models.LogResult
import com.dallaslabs.models.LogStats
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.FindOptions
import io.vertx.ext.mongo.MongoClient
import io.vertx.kotlin.coroutines.coAwait
import mu.KotlinLogging
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.*

private val logger = KotlinLogging.logger {}
private const val COLLECTION = "logs"
private const val EXCEPTIONS_COLLECTION = "exceptions"

/**
 * Service for log-related operations
 */
class LogService(
    private val vertx: Vertx,
    private val mongoClient: MongoClient
) {
    private val serverIdentity = getServerIdentity()

    init {
        // Log startup information for consistency with Node.js implementation
        vertx.runOnContext {
            logStartupInfo()
        }
    }

    private fun logStartupInfo() {
        val runtime = Runtime.getRuntime()
        val memoryMB = runtime.totalMemory() / (1024 * 1024)
        val freeMB = runtime.freeMemory() / (1024 * 1024)

        val startupInfo = JsonObject()
            .put("message", "Server starting")
            .put("level", "info")
            .put("timestamp", Date())
            .put("serverId", serverIdentity["hostAddress"])
            .put("hostname", serverIdentity["hostname"])
            .put("environment", System.getProperty("env", "development"))
            .put("application", "vertx-server")
            .put("version", "1.0.0")
            .put("javaVersion", System.getProperty("java.version"))
            .put("platform", System.getProperty("os.name"))
            .put("arch", System.getProperty("os.arch"))
            .put("cpuCores", runtime.availableProcessors())
            .put("totalMemory", "$memoryMB MB")
            .put("freeMemory", "$freeMB MB")

        vertx.executeBlocking<Void>({ promise ->
            try {
                mongoClient.insert(COLLECTION, startupInfo)
                promise.complete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to log startup info" }
                promise.fail(e)
            }
        }, {})
    }

    suspend fun getAvailableServers(): List<String> {
        return mongoClient
            .distinct(COLLECTION, "serverId", "java.lang.String")
            .coAwait()
            .toList()
            .map { it.toString() }
    }

    suspend fun getAvailableLevels(): List<String> {
        return mongoClient
            .distinct(COLLECTION, "level", "java.lang.String")
            .coAwait()
            .toList()
            .map { it.toString() }
    }

    /**
     * Gets logs with pagination and filtering
     */
    suspend fun getLogs(
        page: Int = 1,
        limit: Int = 50,
        serverId: String? = null,
        level: String? = null,
        message: String? = null,
        requestId: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        nodeService: NodeService? = null
    ): LogResult {
        // Existing implementation - no changes
        val query = JsonObject()

        // Handle serverId mapping to node name/host - only do this once!
        if (!serverId.isNullOrBlank()) {
            if (nodeService != null) {
                try {
                    // Try to map server name to host
                    val node = nodeService.getNodes().find { it.name == serverId }
                    if (node != null) {
                        // We might find serverId in different locations, so use $or
                        val conditions = JsonArray()

                        // Direct serverId field
                        conditions.add(JsonObject().put("serverId", node.host))

                        // metadata.serverId field
                        conditions.add(JsonObject().put("metadata.serverId", node.host))

                        // metadata.metadata.serverId field
                        conditions.add(JsonObject().put("metadata.metadata.serverId", node.host))

                        query.put("\$or", conditions)
                    } else {
                        // If no node matches, use the serverId directly
                        query.put("serverId", serverId)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to map node name to host: $serverId" }
                    // Fallback to direct serverId
                    query.put("serverId", serverId)
                }
            } else {
                // No nodeService available
                query.put("serverId", serverId)
            }
        }

        if (!level.isNullOrBlank()) {
            query.put("level", level)
        }

        if (!message.isNullOrBlank()) {
            query.put("message", JsonObject().put("\$regex", message).put("\$options", "i"))
        }

        if (!requestId.isNullOrBlank()) {
            query.put("requestId", requestId)
        }

        // Handle date range if provided
        if (!startDate.isNullOrBlank() || !endDate.isNullOrBlank()) {
            val dateQuery = JsonObject()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")

            if (!startDate.isNullOrBlank()) {
                try {
                    val date = dateFormat.parse(startDate)
                    dateQuery.put("\$gte", date)
                } catch (e: Exception) {
                    logger.error { "Failed to parse start date: $startDate" }
                }
            }

            if (!endDate.isNullOrBlank()) {
                try {
                    val date = dateFormat.parse(endDate)
                    dateQuery.put("\$lte", date)
                } catch (e: Exception) {
                    logger.error { "Failed to parse end date: $endDate" }
                }
            }

            if (!dateQuery.isEmpty) {
                query.put("timestamp", dateQuery)
            }
        }

        logger.debug { "MongoDB query: ${query.encode()}" }

        val options = FindOptions()
            .setSort(JsonObject().put("timestamp", -1))
            .setSkip((page - 1) * limit)
            .setLimit(limit)

        val documents = mongoClient.findWithOptions(COLLECTION, query, options).coAwait()
        val logs = documents.map { Log(it) }

        val count = mongoClient.count(COLLECTION, query).coAwait()

        return LogResult(logs, count)
    }

    /**
     * Gets log statistics
     */
    suspend fun getLogStats(): LogStats {
        // Existing implementation - no changes
        val totalLogs = mongoClient.count(COLLECTION, JsonObject()).coAwait()
        val errorCount = mongoClient.count(COLLECTION, JsonObject().put("level", "error")).coAwait()
        val warnCount = mongoClient.count(COLLECTION, JsonObject().put("level", "warn")).coAwait()
        val infoCount = mongoClient.count(COLLECTION, JsonObject().put("level", "info")).coAwait()
        val serverCount = getAvailableServers().size

        return LogStats(
            totalLogs = totalLogs,
            errorCount = errorCount,
            warnCount = warnCount,
            infoCount = infoCount,
            serverCount = serverCount
        )
    }

    /**
     * Log a message to MongoDB
     * @param level Log level (info, warn, error, debug)
     * @param message Log message
     * @param metadata Additional metadata for the log entry
     */
    suspend fun log(
        level: String,
        message: String,
        metadata: Map<String, Any> = emptyMap()
    ) {
        try {
            // Enhanced log format to match Node.js implementation
            val logEntry = JsonObject()
                .put("level", level)
                .put("message", message)
                .put("timestamp", Date())
                .put("serverId", serverIdentity["hostAddress"])
                .put("hostname", serverIdentity["hostname"])
                .put("environment", System.getProperty("env", "development"))
                .put("application", "vertx-server")
                .put("version", "1.0.0")

            // Keep metadata in one place for consistency
            val metadataObj = JsonObject()
            metadata.forEach { (key, value) ->
                metadataObj.put(key, value)
            }

            logEntry.put("metadata", metadataObj)

            mongoClient.insert(COLLECTION, logEntry).coAwait()
            logger.debug { "Log entry inserted successfully: $message" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to insert log entry to MongoDB" }
        }
    }

    /**
     * Log a system event
     * @param message Event message
     * @param type Event type (startup, shutdown, etc.)
     */
    suspend fun logSystemEvent(
        message: String,
        type: String = "system"
    ) {
        // Existing implementation - no changes
        log("info", message, mapOf(
            "eventType" to type,
            "serverId" to getServerIdentity()
        ))
    }

    /**
     * Log an error event
     * @param message Error message
     * @param error Optional exception
     * @param context Additional context information
     */
    suspend fun logError(
        message: String,
        error: Throwable? = null,
        context: Map<String, Any> = emptyMap()
    ) {
        // Existing implementation with enhancement to also log to exceptions collection
        val errorMetadata = mutableMapOf<String, Any>(
            "errorMessage" to (error?.message ?: "Unknown error")
        )
        errorMetadata.putAll(context)

        error?.let {
            errorMetadata["stackTrace"] = it.stackTraceToString()
        }

        log("error", message, errorMetadata)

        // Also log to exceptions collection
        if (error != null) {
            logException(message, error, context)
        }
    }

    /**
     * Log an exception to the exceptions collection
     * @param message Error message
     * @param throwable The exception
     * @param context Additional context information
     */
    private suspend fun logException(
        message: String,
        throwable: Throwable,
        context: Map<String, Any> = emptyMap()
    ) {
        try {
            val exceptionEntry = JsonObject()
                .put("level", "error")
                .put("message", message)
                .put("timestamp", Date())
                .put("serverId", serverIdentity["hostAddress"])
                .put("hostname", serverIdentity["hostname"])
                .put("environment", System.getProperty("env", "development"))
                .put("application", "vertx-server")
                .put("version", "1.0.0")
                .put("error", throwable.message)
                .put("stack", throwable.stackTraceToString())

            // Add context as metadata
            val metadataObj = JsonObject()
            context.forEach { (key, value) ->
                metadataObj.put(key, value)
            }

            exceptionEntry.put("metadata", metadataObj)

            mongoClient.insert(EXCEPTIONS_COLLECTION, exceptionEntry).coAwait()
        } catch (e: Exception) {
            logger.error(e) { "Failed to insert exception to MongoDB: $message" }
        }
    }

    /**
     * Log request details for incoming request
     * Format compatible with Node.js server
     */
    fun logRequest(
        requestId: String,
        method: String,
        url: String,
        path: String,
        query: Map<String, String>,
        ip: String,
        userAgent: String? = null,
        contentType: String? = null,
        body: JsonObject? = null
    ) {
        // Sanitize the request body
        val sanitizedBody = if (body != null) sanitizeRequestBody(body) else null

        val metadata = mutableMapOf<String, Any>(
            "requestId" to requestId,
            "method" to method,
            "url" to url,
            "path" to path,
            "ip" to ip
        )

        if (query.isNotEmpty()) {
            metadata["query"] = JsonObject(query)
        }

        if (userAgent != null) {
            metadata["userAgent"] = userAgent
        }

        if (contentType != null) {
            metadata["contentType"] = contentType
        }

        if (sanitizedBody != null) {
            metadata["requestBody"] = sanitizedBody
        }

        // Use vertx.executeBlocking to avoid blocking the event loop
        vertx.executeBlocking<Void>({ promise ->
            try {
                val logEntry = JsonObject()
                    .put("level", "info")
                    .put("message", "Request received")
                    .put("timestamp", Date())
                    .put("serverId", serverIdentity["hostAddress"])
                    .put("hostname", serverIdentity["hostname"])
                    .put("environment", System.getProperty("env", "development"))
                    .put("application", "vertx-server")
                    .put("version", "1.0.0")
                    .put("requestId", requestId)

                // Add all metadata fields directly to the root
                metadata.forEach { (key, value) ->
                    if (key != "requestId") { // Already added above
                        logEntry.put(key, value)
                    }
                }

                mongoClient.insert(COLLECTION, logEntry)
                promise.complete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to log request" }
                promise.fail(e)
            }
        }, {})
    }

    /**
     * Log response details for outgoing response
     * Format compatible with Node.js server
     */
    fun logResponse(
        requestId: String,
        method: String,
        url: String,
        statusCode: Int,
        duration: Long,
        contentType: String? = null
    ) {
        // Use vertx.executeBlocking to avoid blocking the event loop
        vertx.executeBlocking<Void>({ promise ->
            try {
                val logEntry = JsonObject()
                    .put("level", "info")
                    .put("message", "Response sent")
                    .put("timestamp", Date())
                    .put("serverId", serverIdentity["hostAddress"])
                    .put("hostname", serverIdentity["hostname"])
                    .put("environment", System.getProperty("env", "development"))
                    .put("application", "vertx-server")
                    .put("version", "1.0.0")
                    .put("requestId", requestId)
                    .put("method", method)
                    .put("url", url)
                    .put("statusCode", statusCode)
                    .put("duration", "${duration}ms")
                    .put("responseTime", duration)

                if (contentType != null) {
                    logEntry.put("contentType", contentType)
                }

                mongoClient.insert(COLLECTION, logEntry)
                promise.complete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to log response" }
                promise.fail(e)
            }
        }, {})
    }

    /**
     * Log system health statistics periodically
     */
    fun logSystemHealth() {
        val runtime = Runtime.getRuntime()
        val memoryMB = runtime.totalMemory() / (1024 * 1024)
        val freeMB = runtime.freeMemory() / (1024 * 1024)
        val usedMB = memoryMB - freeMB

        // Use vertx.executeBlocking to avoid blocking the event loop
        vertx.executeBlocking<Void>({ promise ->
            try {
                val logEntry = JsonObject()
                    .put("level", "info")
                    .put("message", "System health stats")
                    .put("timestamp", Date())
                    .put("serverId", serverIdentity["hostAddress"])
                    .put("hostname", serverIdentity["hostname"])
                    .put("environment", System.getProperty("env", "development"))
                    .put("application", "vertx-server")
                    .put("version", "1.0.0")
                    .put("uptime", ManagementFactory.getRuntimeMXBean().uptime / 1000)
                    .put("memoryUsage", JsonObject()
                        .put("rss", "${memoryMB} MB")
                        .put("heapTotal", "${memoryMB} MB")
                        .put("heapUsed", "${usedMB} MB")
                        .put("external", "N/A")
                    )
                    .put("cpuUsage", JsonObject())
                    .put("activeRequests", "N/A")
                    .put("queuedRequests", "N/A")

                mongoClient.insert(COLLECTION, logEntry)
                promise.complete()
            } catch (e: Exception) {
                logger.error(e) { "Failed to log system health" }
                promise.fail(e)
            }
        }, {})
    }

    /**
     * Sanitize request body to avoid logging sensitive information
     */
    private fun sanitizeRequestBody(body: JsonObject): JsonObject {
        if (body.isEmpty) return JsonObject()

        // Create a copy of the body
        val sanitized = body.copy()

        // Remove potentially sensitive fields
        val sensitiveFields = listOf("password", "token", "apiKey", "secret")
        for (field in sensitiveFields) {
            if (sanitized.containsKey(field)) {
                sanitized.put(field, "[REDACTED]")
            }
        }

        // Truncate large text fields
        if (sanitized.containsKey("prompt") && sanitized.getString("prompt")?.length ?: 0 > 100) {
            val prompt = sanitized.getString("prompt")
            sanitized.put("prompt", prompt?.substring(0, 100) + "... [TRUNCATED]")
        }

        // For message arrays, truncate content
        if (sanitized.containsKey("messages") && sanitized.getJsonArray("messages") != null) {
            val messages = sanitized.getJsonArray("messages")
            for (i in 0 until messages.size()) {
                val msg = messages.getJsonObject(i)
                if (msg.containsKey("content") && msg.getString("content")?.length ?: 0 > 100) {
                    val content = msg.getString("content")
                    msg.put("content", content?.substring(0, 100) + "... [TRUNCATED]")
                }
            }
        }

        return sanitized
    }

    /**
     * Get server identity for logging
     */
    private fun getServerIdentity(): Map<String, String> {
        return try {
            val hostname = java.net.InetAddress.getLocalHost().hostName
            val hostAddress = java.net.InetAddress.getLocalHost().hostAddress

            mapOf(
                "hostname" to hostname,
                "hostAddress" to hostAddress,
                "os" to System.getProperty("os.name"),
                "arch" to System.getProperty("os.arch")
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to get server identity" }
            mapOf(
                "hostname" to "unknown",
                "hostAddress" to "unknown",
                "os" to System.getProperty("os.name"),
                "arch" to System.getProperty("os.arch")
            )
        }
    }
}
