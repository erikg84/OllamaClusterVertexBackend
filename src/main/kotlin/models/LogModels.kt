package com.dallaslabs.models

import com.dallaslabs.services.NodeService
import io.vertx.core.json.JsonObject
import java.time.Instant
import java.util.*

/**
 * Represents a log entry
 */
data class Log(
    val id: String = "",
    val timestamp: Date,
    val level: String,
    val message: String,
    val serverId: String? = null,
    val hostname: String? = null,
    val environment: String? = null,
    val requestId: String? = null,
    val method: String? = null,
    val url: String? = null,
    val statusCode: Int? = null,
    val duration: Long? = null,
    val metadata: JsonObject? = null
) {
    // Constructor to convert from JsonObject
    constructor(json: JsonObject) : this(
        id = json.getString("_id", ""),
        timestamp = parseTimestamp(json),
        level = json.getString("level", "unknown"),
        message = json.getString("message", ""),
        serverId = extractServerId(json),
        hostname = extractHostname(json),
        environment = extractEnvironment(json),
        requestId = json.getString("requestId"),
        method = json.getString("method"),
        url = json.getString("url"),
        statusCode = json.getInteger("statusCode"),
        duration = json.getLong("duration"),
        metadata = json.getJsonObject("metadata")
    )

    // Convert to JsonObject for response, optionally mapping serverId to node name
    fun toJson(nodeService: NodeService? = null): JsonObject {
        return JsonObject()
            .put("id", id)
            .put("timestamp", timestamp.time)
            .put("level", level)
            .put("message", message)
            .put("serverId", getServerDisplayName(nodeService))
            .also {
                hostname?.let { h -> it.put("hostname", h) }
                environment?.let { e -> it.put("environment", e) }
                requestId?.let { r -> it.put("requestId", r) }
                method?.let { m -> it.put("method", m) }
                url?.let { u -> it.put("url", u) }
                statusCode?.let { s -> it.put("statusCode", s) }
                duration?.let { d -> it.put("duration", d) }
                metadata?.let { m -> it.put("metadata", m) }
            }
    }

    // Get a display name for the server, using NodeService to map IPs to names
    private fun getServerDisplayName(nodeService: NodeService?): String {
        if (nodeService == null || serverId.isNullOrBlank()) {
            return serverId ?: "Unknown"
        }

        // Try to find a node with this host
        val node = nodeService.getNodes().find { it.host == serverId }
        return node?.name ?: serverId
    }

    companion object {
        // Parse timestamp from various formats
        private fun parseTimestamp(json: JsonObject): Date {
            return try {
                when {
                    // Direct Date object in timestamp field
                    json.getValue("timestamp") is JsonObject && json.getJsonObject("timestamp").containsKey("date") -> {
                        val dateObj = json.getJsonObject("timestamp")
                        val dateStr = dateObj.getString("date")
                        Date.from(Instant.parse(dateStr))
                    }
                    // ISO date string in timestamp field
                    json.getString("timestamp") != null -> {
                        try {
                            Date.from(Instant.parse(json.getString("timestamp")))
                        } catch (e: Exception) {
                            Date()
                        }
                    }
                    // Long value in timestamp field
                    json.getLong("timestamp") != null -> {
                        Date(json.getLong("timestamp"))
                    }
                    // Fallback
                    else -> Date()
                }
            } catch (e: Exception) {
                Date()
            }
        }

        // Extract serverId from document or metadata
        private fun extractServerId(json: JsonObject): String? {
            // Direct serverId field
            json.getString("serverId")?.let { return it }

            // Look for serverId in metadata
            val metadata = json.getJsonObject("metadata")
            if (metadata != null) {
                // If metadata has direct serverId field
                metadata.getString("serverId")?.let { return it }

                // Check for nested metadata.metadata.serverId
                val nestedMetadata = metadata.getJsonObject("metadata")
                nestedMetadata?.getString("serverId")?.let { return it }
            }

            return null
        }

        // Extract hostname from document or metadata
        private fun extractHostname(json: JsonObject): String? {
            json.getString("hostname")?.let { return it }

            val metadata = json.getJsonObject("metadata")
            if (metadata != null) {
                metadata.getString("hostname")?.let { return it }

                val nestedMetadata = metadata.getJsonObject("metadata")
                nestedMetadata?.getString("hostname")?.let { return it }
            }

            return null
        }

        // Extract environment from document or metadata
        private fun extractEnvironment(json: JsonObject): String? {
            json.getString("environment")?.let { return it }

            val metadata = json.getJsonObject("metadata")
            if (metadata != null) {
                metadata.getString("environment")?.let { return it }

                val nestedMetadata = metadata.getJsonObject("metadata")
                nestedMetadata?.getString("environment")?.let { return it }
            }

            return null
        }
    }
}

/**
 * Represents a result of a log query with pagination
 */
data class LogResult(
    val logs: List<Log>,
    val total: Long
)

/**
 * Represents log statistics
 */
data class LogStats(
    val totalLogs: Long,
    val errorCount: Long,
    val warnCount: Long,
    val infoCount: Long,
    val serverCount: Int
)
