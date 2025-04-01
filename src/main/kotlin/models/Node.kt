package com.dallaslabs.models

/**
 * Represents an LLM node in the cluster
 */
data class Node(
    val name: String,
    val host: String,
    val port: Int,
    val type: String,
    val platform: String,
    val capabilities: List<String>
)

/**
 * Represents the status of a node
 */
data class NodeStatus(
    val node: Node,
    val status: String,
    val message: String? = null
)
