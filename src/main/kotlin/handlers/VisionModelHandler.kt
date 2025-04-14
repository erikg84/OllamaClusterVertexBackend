package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.services.LogService
import com.dallaslabs.services.ModelRegistryService
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.*

private val logger = KotlinLogging.logger {}

class VisionModelHandler(
    private val vertx: Vertx,
    private val modelRegistry: ModelRegistryService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    fun getVisionModels(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: "unknown"
        logger.info { "Vision models requested (requestId: $requestId)" }

        launch {
            logService.log("info", "Vision models requested", mapOf("requestId" to requestId))
            try {
                val allModels = modelRegistry.getAllModels()

                // Filter vision-capable models based on their names
                val visionModels = allModels.filter { model ->
                    val modelId = model.id.lowercase(Locale.getDefault())
                    modelId.contains("llava") ||
                            modelId.contains("vision") ||
                            modelId.contains("bakllava") ||
                            modelId.contains("llama3.2-vision")
                }

                val response = ApiResponse.success(
                    data = visionModels,
                    message = "Vision models: ${visionModels.size} found"
                )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } catch (e: Exception) {
                logger.error(e) { "Failed to get vision models (requestId: $requestId)" }
                logService.logError("Failed to get vision models", e, mapOf("requestId" to requestId))

                val response = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }
    }
}
