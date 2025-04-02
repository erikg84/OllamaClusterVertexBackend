package com.dallaslabs.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * Represents a request to generate text
 */
data class GenerateRequest(
    val model: String = "",  // Add default values
    val prompt: String = "",
    val node: String = "",   // Add the node parameter that's in your JSON
    val stream: Boolean = false
)

/**
 * Represents a chat request
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatRequest(
    val model: String = "",  // Add default values
    val messages: List<Message> = emptyList(),
    val node: String = "",   // Add the node parameter
    val stream: Boolean = false
)

/**
 * Represents a chat message
 */
data class Message(
    val role: String = "",    // Add default value
    val content: String = ""  // Add default value
)

/**
 * Represents model status
 */
enum class ModelStatus {
    AVAILABLE, UNAVAILABLE, UNKNOWN
}

/**
 * Represents detailed model information
 */
data class ModelDetails(
    val family: String? = null,
    val parameterSize: String? = null,
    val quantizationLevel: String? = null,
    val format: String? = null,
    val modified: String? = null
)

/**
 * Represents a model in the frontend format
 */
data class Model(
    val id: String,
    val name: String,
    val node: String? = null,
    val status: ModelStatus? = ModelStatus.UNKNOWN,
    val details: ModelDetails? = null
)

/**
 * Extension function to convert ModelInfo to frontend Model format
 */
fun ModelInfo.toFrontendModel(nodeList: List<String>): Model {
    val parameterSize = when {
        size > 1_000_000_000 -> "${size / 1_000_000_000}B"
        size > 1_000_000 -> "${size / 1_000_000}M"
        else -> "${size} bytes"
    }

    return Model(
        id = id,
        name = name,
        node = nodeList.firstOrNull(),
        status = if (nodeList.isNotEmpty()) ModelStatus.AVAILABLE else ModelStatus.UNAVAILABLE,
        details = ModelDetails(
            family = type,
            parameterSize = parameterSize,
            quantizationLevel = quantization,
            format = "gguf",  // Default format for Ollama models
            modified = null   // We don't have this information in the current ModelInfo
        )
    )
}
