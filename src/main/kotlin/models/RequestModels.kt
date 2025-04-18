package com.dallaslabs.models

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.vertx.core.json.JsonObject

/**
 * Represents a request to generate text
 */
data class GenerateRequest(
    val model: String = "",  // Add default values
    val prompt: String = "",
    val node: String = "",   // Add the node parameter that's in your JSON
    val stream: Boolean = false,
    val options: JsonObject? = null
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
 * Enum defining different processing strategies for document handling
 */
enum class ProcessingStrategy {
    DIRECT,             // Process each document directly in the orchestration service
    NODE_BACKEND,       // Send documents to Node.js backend for processing
    DISTRIBUTED,        // Distribute documents across multiple nodes
    COMBINED_CHUNKING   // Process documents by combining and chunking
}

/**
 * Information about a document being processed
 */
data class DocumentInfo(
    val id: String,              // Unique identifier for this document
    val filename: String,        // Original filename
    val contentType: String,     // MIME type of the document
    val text: String,            // Extracted text content
    val size: Long,              // Size in bytes
    val uploadPath: String,      // Path to the uploaded file
    val metadata: JsonObject = JsonObject()  // Additional metadata
)

/**
 * Result of processing a single document
 */
data class DocumentResult(
    val documentId: String,         // ID of the document this result is for
    val filename: String,           // Original filename
    val content: String,            // Processed content/output
    val processingTimeMs: Long,     // Processing time in milliseconds
    val metadata: JsonObject = JsonObject(),  // Additional metadata about processing
    val originalText: String? = null // Optional original text (for reference)
)

/**
 * Result of synthesizing multiple document results
 */
data class SynthesisResult(
    val content: String,            // Synthesized content
    val processingTimeMs: Long,     // Processing time in milliseconds
    val metadata: JsonObject = JsonObject()  // Additional metadata
)