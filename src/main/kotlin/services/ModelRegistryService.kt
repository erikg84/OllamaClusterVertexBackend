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

/**
 * Registry for models across the cluster
 * Maintains information about which models are available on which nodes
 */
class ModelRegistryService(
    private val vertx: Vertx,
    private val nodeService: NodeService,
    private val logService: LogService
) : CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = vertx.dispatcher()

    // Map from modelId to a map of nodeNames to ModelInfo
    private val modelRegistry = ConcurrentHashMap<String, ConcurrentHashMap<String, ModelInfo>>()

    // Store model sizes for selection logic
    private val modelSizes = ConcurrentHashMap<String, Long>()

    // Last refresh timestamp
    private var lastRefresh: Long = 0

    init {
        // Schedule periodic refresh
        vertx.setPeriodic(60000) { // Every minute
            launch(coroutineContext) {
                refreshRegistry()
            }
        }
    }

    /**
     * Refreshes the model registry by querying all nodes
     */
    suspend fun refreshRegistry() {
       logger.info { "Refreshing model registry" }

        try {
            val nodeStatuses = nodeService.getAllNodesStatus().coAwait()
            val onlineNodes = nodeStatuses.filter { it.status == "online" }.map { it.node }

            // Create a new registry to replace the old one
            val newRegistry = ConcurrentHashMap<String, ConcurrentHashMap<String, ModelInfo>>()
            val newModelSizes = ConcurrentHashMap<String, Long>()

            for (node in onlineNodes) {
                try {
                    val nodeModels = nodeService.getNodeModels(node.name).coAwait()

                    for (model in nodeModels) {
                        // Update model sizes map
                        newModelSizes[model.id] = model.size

                        // Add model to registry
                        val nodesForModel = newRegistry.computeIfAbsent(model.id) {
                            ConcurrentHashMap<String, ModelInfo>()
                        }
                        nodesForModel[node.name] = model.copy(node = node.name)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to get models from node: ${node.name}" }
                    // Continue with other nodes
                }
            }

            // Replace the registry with the new one
            modelRegistry.clear()
            modelRegistry.putAll(newRegistry)

            modelSizes.clear()
            modelSizes.putAll(newModelSizes)

            lastRefresh = System.currentTimeMillis()
            logger.info { "Model registry refreshed. Found ${modelRegistry.size} models across ${onlineNodes.size} nodes." }
        } catch (e: Exception) {
            logger.error(e) { "Failed to refresh model registry" }
        }
    }

    /**
     * Gets all available models
     */
    fun getAllModels(): List<ModelInfo> {
        return modelRegistry.flatMap { (_, nodeMap) ->
            nodeMap.values
        }
    }

    /**
     * Gets all nodes that have a specific model
     */
    fun getNodesForModel(modelId: String): List<String> {
        return modelRegistry[modelId]?.keys?.toList() ?: emptyList()
    }

    /**
     * Gets the model info for a specific model on a specific node
     */
    fun getModelInfo(modelId: String, nodeName: String): ModelInfo? {
        return modelRegistry[modelId]?.get(nodeName)
    }

    /**
     * Gets the size of a model
     */
    fun getModelSize(modelId: String): Long {
        return modelSizes[modelId] ?: 0
    }

    /**
     * Checks if a model is available anywhere in the cluster
     */
    fun isModelAvailable(modelId: String): Boolean {
        return modelRegistry.containsKey(modelId) && modelRegistry[modelId]?.isNotEmpty() == true
    }

    /**
     * Forces an immediate refresh of the registry
     */
    suspend fun forceRefresh() {
        refreshRegistry()
    }
}
