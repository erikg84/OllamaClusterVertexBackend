package com.dallaslabs.models

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

