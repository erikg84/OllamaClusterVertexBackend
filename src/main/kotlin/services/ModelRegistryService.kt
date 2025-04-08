package com.dallaslabs.services

import com.dallaslabs.models.ModelInfo
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

class ModelRegistryService(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    private val modelRegistry = ConcurrentHashMap<String, ConcurrentHashMap<String, ModelInfo>>()
    private val modelSizes = ConcurrentHashMap<String, Long>()
    private var lastRefresh: Long = 0

    init {
        vertx.setPeriodic(60000) {
            launch(coroutineContext) {
                refreshRegistry()
            }
        }
    }

    suspend fun refreshRegistry() {
        logger.info { "Refreshing model registry" }
        logService.log("info", "Refreshing model registry", emptyMap())

        try {
            val nodeStatuses = nodeService.getAllNodesStatus().coAwait()
            val onlineNodes = nodeStatuses.filter { it.status == "online" }.map { it.node }

            val newRegistry = ConcurrentHashMap<String, ConcurrentHashMap<String, ModelInfo>>()
            val newModelSizes = ConcurrentHashMap<String, Long>()

            for (node in onlineNodes) {
                try {
                    val nodeModels = nodeService.getNodeModels(node.name).coAwait()

                    for (model in nodeModels) {
                        newModelSizes[model.id] = model.size

                        val nodesForModel = newRegistry.computeIfAbsent(model.id) {
                            ConcurrentHashMap<String, ModelInfo>()
                        }
                        nodesForModel[node.name] = model.copy(node = node.name)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get models from node: ${node.name}" }
                    logService.logError("Failed to get models from node", e, mapOf("nodeName" to node.name))
                }
            }

            modelRegistry.clear()
            modelRegistry.putAll(newRegistry)

            modelSizes.clear()
            modelSizes.putAll(newModelSizes)

            lastRefresh = System.currentTimeMillis()
            logger.info { "Model registry refreshed. Found ${modelRegistry.size} models across ${onlineNodes.size} nodes." }
            logService.log("info", "Model registry refreshed", mapOf(
                "modelCount" to modelRegistry.size,
                "nodeCount" to onlineNodes.size
            ))
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh model registry" }
            logService.logError("Failed to refresh model registry", e)
        }
    }

    fun getAllModels(): List<ModelInfo> {
        launch {
            logService.log("debug", "Retrieving all models", mapOf("modelCount" to modelRegistry.size))
        }
        return modelRegistry.flatMap { (_, nodeMap) ->
            nodeMap.values
        }
    }

    fun getNodesForModel(modelId: String): List<String> {
        val nodes = modelRegistry[modelId]?.keys?.toList() ?: emptyList()
        launch {
            logService.log("debug", "Retrieved nodes for model", mapOf("modelId" to modelId, "nodeCount" to nodes.size))
        }
        return nodes
    }

    fun getModelInfo(modelId: String, nodeName: String): ModelInfo? {
        val modelInfo = modelRegistry[modelId]?.get(nodeName)
        launch {
            logService.log("debug", "Retrieved model info", mapOf("modelId" to modelId, "nodeName" to nodeName, "found" to (modelInfo != null)))
        }
        return modelInfo
    }

    fun getModelSize(modelId: String): Long {
        val size = modelSizes[modelId] ?: 0
        launch {
            logService.log("debug", "Retrieved model size", mapOf("modelId" to modelId, "size" to size))
        }
        return size
    }

    fun isModelAvailable(modelId: String): Boolean {
        val available = modelRegistry.containsKey(modelId) && modelRegistry[modelId]?.isNotEmpty() == true
        launch {
            logService.log("debug", "Checked model availability", mapOf("modelId" to modelId, "available" to available))
        }
        return available
    }

    suspend fun forceRefresh() {
        logService.log("info", "Force refreshing model registry", emptyMap())
        refreshRegistry()
    }
}
