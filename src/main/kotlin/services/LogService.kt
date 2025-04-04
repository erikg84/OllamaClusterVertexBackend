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
import java.text.SimpleDateFormat
import java.util.*

private val logger = KotlinLogging.logger {}
private const val COLLECTION = "logs"

/**
 * Service for log-related operations
 */
class LogService(
    private val vertx: Vertx,
    private val mongoClient: MongoClient
) {
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
}
