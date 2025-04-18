package com.dallaslabs.services

import com.dallaslabs.models.*
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * TaskDecompositionService analyzes incoming requests and breaks them down
 * into subtasks with dependencies, allowing for more efficient parallel processing
 * and specialized handling of complex queries.
 */
class TaskDecompositionService(
    private val vertx: Vertx,
    private val logService: LogService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    // Store active task workflows
    private val activeWorkflows = ConcurrentHashMap<String, TaskWorkflow>()

    /**
     * Analyzes a request and decomposes it into subtasks if appropriate
     * @param request The original request (Chat or Generate)
     * @return A TaskWorkflow containing subtasks and their dependencies
     */
    suspend fun decomposeRequest(request: Any): TaskWorkflow {
        val workflowId = UUID.randomUUID().toString()
        logger.info { "Decomposing request into subtasks (workflowId: $workflowId)" }

        logService.log("info", "Decomposing request into subtasks", mapOf(
            "workflowId" to workflowId,
            "requestType" to request.javaClass.simpleName
        ))

        val workflow = when (request) {
            is ChatRequest -> decomposeChatRequest(request, workflowId)
            is GenerateRequest -> decomposeGenerateRequest(request, workflowId)
            else -> {
                logger.warn { "Unknown request type: ${request.javaClass.name}" }
                // Create a single-task workflow for unknown request types
                createSingleTaskWorkflow(request, workflowId)
            }
        }

        activeWorkflows[workflowId] = workflow

        logService.log("info", "Request decomposed successfully", mapOf(
            "workflowId" to workflowId,
            "taskCount" to workflow.tasks.size,
            "decompositionStrategy" to workflow.decompositionStrategy
        ))

        return workflow
    }

    /**
     * Creates a workflow for processing multiple documents
     */
    fun createMultiDocumentWorkflow(
        requestId: String,
        documents: List<DocumentInfo>,
        prompt: String,
        workflowType: String
    ): TaskWorkflow {
        val workflowId = "$requestId-workflow"
        logger.info { "Creating multi-document workflow (workflowId: $workflowId, documentCount: ${documents.size})" }

        launch {
            logService.log("info", "Creating multi-document workflow", mapOf(
                "workflowId" to workflowId,
                "documentCount" to documents.size,
                "workflowType" to workflowType
            ))
        }

        // Determine if the workflow should be sequential or parallel based on document type and size
        val strategy = when {
            documents.size > 5 -> DecompositionStrategy.PARALLEL
            documents.any { it.contentType.contains("application/pdf") && it.text.length > 20000 } ->
                DecompositionStrategy.SPECIALIZED_AGENTS
            documents.sumOf { it.text.length } > 100000 -> DecompositionStrategy.SEQUENTIAL
            else -> DecompositionStrategy.PARALLEL
        }

        val tasks = mutableListOf<Task>()

        // Create a task for each document
        documents.forEachIndexed { index, docInfo ->
            val taskId = "$workflowId-doc-$index"
            val taskContent = "Process document: ${docInfo.filename}\n\nPrompt: $prompt"

            val task = Task(
                id = taskId,
                type = determineTaskTypeForDocument(docInfo),
                content = taskContent,
                dependencies = emptySet(),
                status = TaskStatus.PENDING,
                originalRequest = JsonObject()
                    .put("documentId", docInfo.id)
                    .put("prompt", prompt)
                    .put("type", "document_processing"),
                deadline = System.currentTimeMillis() + 60000, // 1 minute deadline
                metadata = JsonObject()
                    .put("documentId", docInfo.id)
                    .put("filename", docInfo.filename)
                    .put("contentType", docInfo.contentType)
                    .put("size", docInfo.size)
            )

            tasks.add(task)
        }

        // Add a synthesis task that depends on all document tasks
        val docTaskIds = tasks.map { it.id }.toSet()
        val synthesisTaskId = "$workflowId-synthesis"

        val synthesisTask = Task(
            id = synthesisTaskId,
            type = TaskType.SYNTHESIS,
            content = "Synthesize results from all documents\n\nPrompt: $prompt",
            dependencies = docTaskIds,
            status = TaskStatus.PENDING,
            originalRequest = JsonObject()
                .put("prompt", prompt)
                .put("type", "document_synthesis"),
            deadline = System.currentTimeMillis() + 120000, // 2 minute deadline
            metadata = JsonObject()
                .put("documentCount", documents.size)
                .put("synthesisType", "multi_document")
        )

        tasks.add(synthesisTask)

        // Create and store the workflow
        val workflow = TaskWorkflow(
            id = workflowId,
            tasks = tasks,
            decompositionStrategy = strategy,
            createdAt = System.currentTimeMillis(),
            originalRequest = JsonObject()
                .put("requestId", requestId)
                .put("documentCount", documents.size)
                .put("prompt", prompt)
                .put("workflowType", workflowType)
        )

        activeWorkflows[workflowId] = workflow

        return workflow
    }

    /**
     * Create a document workflow for a single large document that needs to be chunked
     */
    fun createDocumentWorkflow(
        requestId: String,
        documentText: String,
        prompt: String,
        workflowType: String
    ): TaskWorkflow {
        val workflowId = "$requestId-workflow"
        logger.info { "Creating document workflow for large document (workflowId: $workflowId, textLength: ${documentText.length})" }

        launch {
            logService.log("info", "Creating chunked document workflow", mapOf(
                "workflowId" to workflowId,
                "textLength" to documentText.length,
                "workflowType" to workflowType
            ))
        }

        // Split the document into chunks for processing
        val chunks = splitTextIntoChunks(documentText, 4000, 200)
        val tasks = mutableListOf<Task>()

        // Create a task for each document chunk
        chunks.forEachIndexed { index, chunk ->
            val taskId = "$workflowId-chunk-$index"
            val taskContent = """
            Process document chunk ${index + 1} of ${chunks.size}
            
            Prompt: $prompt
            
            Document content:
            $chunk
        """.trimIndent()

            val task = Task(
                id = taskId,
                type = TaskType.GENERAL,
                content = taskContent,
                dependencies = emptySet(),
                status = TaskStatus.PENDING,
                originalRequest = JsonObject()
                    .put("prompt", prompt)
                    .put("content", chunk)
                    .put("type", "document_chunk_processing")
                    .put("chunkIndex", index)
                    .put("totalChunks", chunks.size),
                deadline = System.currentTimeMillis() + 60000, // 1 minute deadline
                metadata = JsonObject()
                    .put("chunkIndex", index)
                    .put("totalChunks", chunks.size)
                    .put("chunkLength", chunk.length)
            )

            tasks.add(task)
        }

        // Add a synthesis task
        val chunkTaskIds = tasks.map { it.id }.toSet()
        val synthesisTaskId = "$workflowId-synthesis"

        val synthesisTask = Task(
            id = synthesisTaskId,
            type = TaskType.SYNTHESIS,
            content = "Synthesize results from all document chunks\n\nPrompt: $prompt",
            dependencies = chunkTaskIds,
            status = TaskStatus.PENDING,
            originalRequest = JsonObject()
                .put("prompt", prompt)
                .put("type", "document_chunk_synthesis"),
            deadline = System.currentTimeMillis() + 120000, // 2 minute deadline
            metadata = JsonObject()
                .put("chunkCount", chunks.size)
                .put("synthesisType", "chunked_document")
        )

        tasks.add(synthesisTask)

        // Create and store the workflow
        val workflow = TaskWorkflow(
            id = workflowId,
            tasks = tasks,
            decompositionStrategy = DecompositionStrategy.SEQUENTIAL,
            createdAt = System.currentTimeMillis(),
            originalRequest = JsonObject()
                .put("requestId", requestId)
                .put("textLength", documentText.length)
                .put("prompt", prompt)
                .put("workflowType", workflowType)
        )

        activeWorkflows[workflowId] = workflow

        return workflow
    }

    /**
     * Get all final results from tasks in a workflow
     */
    fun getFinalResults(workflowId: String): JsonObject {
        val workflow = activeWorkflows[workflowId] ?: return JsonObject()

        val results = JsonObject()

        workflow.tasks.forEach { task ->
            if (task.result != null && task.status == TaskStatus.COMPLETED) {
                results.put(task.id, task.result)
            }
        }

        return results
    }

    /**
     * Split text into chunks with overlap for processing
     */
    private fun splitTextIntoChunks(
        text: String,
        chunkSize: Int,
        overlap: Int
    ): List<String> {
        val chunks = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[.!?]\\s)"))
        var currentChunk = StringBuilder()

        for (sentence in sentences) {
            if (currentChunk.length + sentence.length > chunkSize) {
                chunks.add(currentChunk.toString())

                // Create overlap by including some previous text
                val overlapText = currentChunk.takeLast(overlap).toString()
                currentChunk = StringBuilder(overlapText)
            }

            currentChunk.append(sentence)
        }

        // Add the last chunk if it's not empty
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks
    }

    /**
     * Determine the appropriate task type based on document properties
     */
    private fun determineTaskTypeForDocument(document: DocumentInfo): TaskType {
        return when {
            document.contentType.contains("application/pdf") ->
                TaskType.RESEARCH
            document.contentType.contains("text/x-") ||
                    document.filename.endsWith(".java") ||
                    document.filename.endsWith(".py") ||
                    document.filename.endsWith(".js") ||
                    document.filename.endsWith(".kt") ->
                TaskType.CODE
            document.contentType.contains("image/") ->
                TaskType.CREATIVE
            document.text.length > 10000 ->
                TaskType.RESEARCH
            else ->
                TaskType.GENERAL
        }
    }

    /**
     * Decomposes a chat request into subtasks based on message content
     */
    private fun decomposeChatRequest(request: ChatRequest, workflowId: String): TaskWorkflow {
        // Extract the latest user message content
        val userMessage = request.messages.lastOrNull { it.role == "user" }?.content ?: ""

        // Determine the decomposition strategy based on message content
        val strategy = determineDecompositionStrategy(userMessage)

        return when (strategy) {
            DecompositionStrategy.SEQUENTIAL -> createSequentialWorkflow(request, userMessage, workflowId)
            DecompositionStrategy.PARALLEL -> createParallelWorkflow(request, userMessage, workflowId)
            DecompositionStrategy.SPECIALIZED_AGENTS -> createSpecializedAgentsWorkflow(request, userMessage, workflowId)
            DecompositionStrategy.NONE -> createSingleTaskWorkflow(request, workflowId)
        }
    }

    /**
     * Decomposes a generate request into subtasks based on prompt content
     */
    private fun decomposeGenerateRequest(request: GenerateRequest, workflowId: String): TaskWorkflow {
        val prompt = request.prompt

        // Determine the decomposition strategy based on prompt content
        val strategy = determineDecompositionStrategy(prompt)

        return when (strategy) {
            DecompositionStrategy.SEQUENTIAL -> createSequentialWorkflow(request, prompt, workflowId)
            DecompositionStrategy.PARALLEL -> createParallelWorkflow(request, prompt, workflowId)
            DecompositionStrategy.SPECIALIZED_AGENTS -> createSpecializedAgentsWorkflow(request, prompt, workflowId)
            DecompositionStrategy.NONE -> createSingleTaskWorkflow(request, workflowId)
        }
    }

    /**
     * Analyzes text content to determine the best decomposition strategy
     */
    private fun determineDecompositionStrategy(content: String): DecompositionStrategy {
        // Check for complex reasoning tasks
        if (containsComplexReasoning(content)) {
            return DecompositionStrategy.SEQUENTIAL
        }

        // Check for research-heavy tasks
        if (containsResearchTask(content)) {
            return DecompositionStrategy.SPECIALIZED_AGENTS
        }

        // Check for multi-part independent questions
        if (containsIndependentSubquestions(content)) {
            return DecompositionStrategy.PARALLEL
        }

        // Default to no decomposition for simple requests
        return DecompositionStrategy.NONE
    }

    /**
     * Creates a sequential workflow where each task depends on the previous one
     */
    private fun createSequentialWorkflow(request: Any, content: String, workflowId: String): TaskWorkflow {
        val tasks = mutableListOf<Task>()
        val steps = extractReasoningSteps(content)

        var previousTaskId: String? = null

        for ((index, step) in steps.withIndex()) {
            val taskId = "$workflowId-$index"
            val taskType = determineTaskType(step)

            val dependencies = if (previousTaskId != null) {
                setOf(previousTaskId)
            } else {
                emptySet()
            }

            val task = Task(
                id = taskId,
                type = taskType,
                content = step,
                dependencies = dependencies,
                status = TaskStatus.PENDING,
                originalRequest = createSubRequest(request, step),
                deadline = System.currentTimeMillis() + 30000 // 30 seconds deadline
            )

            tasks.add(task)
            previousTaskId = taskId
        }

        return TaskWorkflow(
            id = workflowId,
            tasks = tasks,
            decompositionStrategy = DecompositionStrategy.SEQUENTIAL,
            createdAt = System.currentTimeMillis(),
            originalRequest = request
        )
    }

    /**
     * Creates a parallel workflow where tasks can be executed independently
     */
    private fun createParallelWorkflow(request: Any, content: String, workflowId: String): TaskWorkflow {
        val tasks = mutableListOf<Task>()
        val subquestions = extractIndependentQuestions(content)

        for ((index, question) in subquestions.withIndex()) {
            val taskId = "$workflowId-$index"
            val taskType = determineTaskType(question)

            val task = Task(
                id = taskId,
                type = taskType,
                content = question,
                dependencies = emptySet(),
                status = TaskStatus.PENDING,
                originalRequest = createSubRequest(request, question),
                deadline = System.currentTimeMillis() + 30000 // 30 seconds deadline
            )

            tasks.add(task)
        }

        // Add a final synthesis task that depends on all others
        val synthesisTaskId = "$workflowId-synthesis"
        val allTaskIds = tasks.map { it.id }.toSet()

        val synthesisTask = Task(
            id = synthesisTaskId,
            type = TaskType.SYNTHESIS,
            content = "Synthesize the results of all subtasks",
            dependencies = allTaskIds,
            status = TaskStatus.PENDING,
            originalRequest = request, // Use the original request for synthesis
            deadline = System.currentTimeMillis() + 60000 // 60 seconds deadline
        )

        tasks.add(synthesisTask)

        return TaskWorkflow(
            id = workflowId,
            tasks = tasks,
            decompositionStrategy = DecompositionStrategy.PARALLEL,
            createdAt = System.currentTimeMillis(),
            originalRequest = request
        )
    }

    /**
     * Creates a workflow with specialized agent tasks (research, reasoning, creative, etc.)
     */
    private fun createSpecializedAgentsWorkflow(request: Any, content: String, workflowId: String): TaskWorkflow {
        val tasks = mutableListOf<Task>()

        // Research task
        val researchTaskId = "$workflowId-research"
        val researchTask = Task(
            id = researchTaskId,
            type = TaskType.RESEARCH,
            content = "Research facts and information needed for: $content",
            dependencies = emptySet(),
            status = TaskStatus.PENDING,
            originalRequest = createSubRequest(request, "Research for: $content"),
            deadline = System.currentTimeMillis() + 30000
        )
        tasks.add(researchTask)

        // Reasoning task
        val reasoningTaskId = "$workflowId-reasoning"
        val reasoningTask = Task(
            id = reasoningTaskId,
            type = TaskType.REASONING,
            content = "Logical analysis and reasoning for: $content",
            dependencies = setOf(researchTaskId),
            status = TaskStatus.PENDING,
            originalRequest = createSubRequest(request, "Analyze: $content"),
            deadline = System.currentTimeMillis() + 30000
        )
        tasks.add(reasoningTask)

        // Creative task (if needed)
        if (needsCreativeTask(content)) {
            val creativeTaskId = "$workflowId-creative"
            val creativeTask = Task(
                id = creativeTaskId,
                type = TaskType.CREATIVE,
                content = "Generate creative content for: $content",
                dependencies = setOf(reasoningTaskId),
                status = TaskStatus.PENDING,
                originalRequest = createSubRequest(request, "Create content for: $content"),
                deadline = System.currentTimeMillis() + 30000
            )
            tasks.add(creativeTask)
        }

        // Critic task
        val criticTaskId = "$workflowId-critic"
        val criticDependencies = tasks.map { it.id }.toSet()
        val criticTask = Task(
            id = criticTaskId,
            type = TaskType.CRITIC,
            content = "Review and critique the outputs for: $content",
            dependencies = criticDependencies,
            status = TaskStatus.PENDING,
            originalRequest = createSubRequest(request, "Review: $content"),
            deadline = System.currentTimeMillis() + 30000
        )
        tasks.add(criticTask)

        // Synthesis task
        val synthesisTaskId = "$workflowId-synthesis"
        val synthesisTask = Task(
            id = synthesisTaskId,
            type = TaskType.SYNTHESIS,
            content = "Synthesize all outputs into a final response",
            dependencies = setOf(criticTaskId),
            status = TaskStatus.PENDING,
            originalRequest = request,
            deadline = System.currentTimeMillis() + 30000
        )
        tasks.add(synthesisTask)

        return TaskWorkflow(
            id = workflowId,
            tasks = tasks,
            decompositionStrategy = DecompositionStrategy.SPECIALIZED_AGENTS,
            createdAt = System.currentTimeMillis(),
            originalRequest = request
        )
    }

    /**
     * Creates a single-task workflow for simple requests that don't need decomposition
     */
    private fun createSingleTaskWorkflow(request: Any, workflowId: String): TaskWorkflow {
        val taskId = "$workflowId-single"
        val task = Task(
            id = taskId,
            type = TaskType.GENERAL,
            content = "Process the entire request",
            dependencies = emptySet(),
            status = TaskStatus.PENDING,
            originalRequest = request,
            deadline = System.currentTimeMillis() + 30000
        )

        return TaskWorkflow(
            id = workflowId,
            tasks = listOf(task),
            decompositionStrategy = DecompositionStrategy.NONE,
            createdAt = System.currentTimeMillis(),
            originalRequest = request
        )
    }

    /**
     * Updates the status of a task within a workflow
     */
    fun updateTaskStatus(workflowId: String, taskId: String, status: TaskStatus, result: JsonObject? = null) {
        val workflow = activeWorkflows[workflowId] ?: return

        val updatedTasks = workflow.tasks.map { task ->
            if (task.id == taskId) {
                task.copy(
                    status = status,
                    result = result,
                    completedAt = if (status == TaskStatus.COMPLETED) System.currentTimeMillis() else null
                )
            } else {
                task
            }
        }

        activeWorkflows[workflowId] = workflow.copy(tasks = updatedTasks)

        launch {
            logService.log("info", "Updated task status", mapOf(
                "workflowId" to workflowId,
                "taskId" to taskId,
                "status" to status.name
            ))
        }

        // Check if workflow is complete
        if (isWorkflowComplete(workflowId)) {
            launch { logService.log("info", "Workflow completed", mapOf("workflowId" to workflowId)) }
        }
    }

    /**
     * Checks if a workflow is complete (all tasks completed or failed)
     */
    fun isWorkflowComplete(workflowId: String): Boolean {
        val workflow = activeWorkflows[workflowId] ?: return false

        return workflow.tasks.all {
            it.status == TaskStatus.COMPLETED || it.status == TaskStatus.FAILED
        }
    }

    /**
     * Gets the next executable tasks (those whose dependencies are satisfied)
     */
    fun getNextExecutableTasks(workflowId: String): List<Task> {
        val workflow = activeWorkflows[workflowId] ?: return emptyList()

        return workflow.tasks.filter { task ->
            // Task must be pending
            task.status == TaskStatus.PENDING &&
                    // All dependencies must be completed
                    task.dependencies.all { dependencyId ->
                        workflow.tasks.find { it.id == dependencyId }?.status == TaskStatus.COMPLETED
                    }
        }
    }

    /**
     * Gets the full task workflow by ID
     */
    fun getWorkflow(workflowId: String): TaskWorkflow? {
        return activeWorkflows[workflowId]
    }

    /**
     * Gets the synthesized final result for a workflow
     */
    fun getFinalResult(workflowId: String): JsonObject? {
        val workflow = activeWorkflows[workflowId] ?: return null

        // Find the synthesis task or the single task for simple workflows
        val finalTask = if (workflow.decompositionStrategy == DecompositionStrategy.NONE) {
            workflow.tasks.firstOrNull()
        } else {
            workflow.tasks.find { it.type == TaskType.SYNTHESIS }
        }

        return finalTask?.result
    }

    /**
     * Cleans up completed workflows older than a specified time
     */
    fun cleanupOldWorkflows(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val currentTime = System.currentTimeMillis()
        val expiredWorkflows = activeWorkflows.entries
            .filter { (_, workflow) ->
                isWorkflowComplete(workflow.id) &&
                        (currentTime - workflow.createdAt) > maxAgeMs
            }
            .map { it.key }

        for (workflowId in expiredWorkflows) {
            activeWorkflows.remove(workflowId)
           launch {  logService.log("info", "Removed expired workflow", mapOf("workflowId" to workflowId)) }
        }

        logger.info { "Cleaned up ${expiredWorkflows.size} expired workflows" }
    }

    // Helper functions for content analysis

    private fun containsComplexReasoning(content: String): Boolean {
        val reasoningKeywords = listOf(
            "step by step", "analyze", "evaluate", "compare", "contrast",
            "pros and cons", "advantages and disadvantages", "explain why",
            "solve this problem", "mathematical", "proof", "logical reasoning",
            "think through", "multi-step", "complex"
        )

        return reasoningKeywords.any { content.contains(it, ignoreCase = true) }
    }

    private fun containsResearchTask(content: String): Boolean {
        val researchKeywords = listOf(
            "research", "information about", "find out", "tell me about",
            "what is", "who is", "when did", "where is", "explain",
            "details on", "facts about", "history of", "describe"
        )

        return researchKeywords.any { content.contains(it, ignoreCase = true) }
    }

    private fun containsIndependentSubquestions(content: String): Boolean {
        // Check for numbered lists
        if (content.contains(Regex("""\d+\.\s+\w+"""))) return true

        // Check for bullet points
        if (content.contains(Regex("""\*\s+\w+"""))) return true

        // Check for multiple questions
        val questionMarks = content.count { it == '?' }
        if (questionMarks > 1) return true

        // Check for phrases that indicate multiple parts
        val multipartPhrases = listOf(
            "multiple questions", "several parts", "different aspects",
            "firstly", "secondly", "lastly", "additionally",
            "on one hand", "on the other hand", "part 1", "part 2"
        )

        return multipartPhrases.any { content.contains(it, ignoreCase = true) }
    }

    private fun extractReasoningSteps(content: String): List<String> {
        // Try to identify logical steps
        val steps = mutableListOf<String>()

        // If we can't identify clear steps, create some default ones
        if (steps.isEmpty()) {
            steps.add("Understand the problem: $content")
            steps.add("Research relevant information for: $content")
            steps.add("Apply logical reasoning to: $content")
            steps.add("Formulate a response for: $content")
        }

        return steps
    }

    private fun extractIndependentQuestions(content: String): List<String> {
        val questions = mutableListOf<String>()

        // Try to extract numbered or bulleted items
        val listedItemsRegex = Regex("""(?:\d+\.|\*)\s+([^.\n]+[.?!])""")
        val matches = listedItemsRegex.findAll(content)
        matches.forEach { questions.add(it.groupValues[1].trim()) }

        // Try to extract multiple question marks
        if (questions.isEmpty()) {
            val questionRegex = Regex("""([^.!?]+\?)""")
            val questionMatches = questionRegex.findAll(content)
            questionMatches.forEach { questions.add(it.groupValues[1].trim()) }
        }

        // If we still don't have subquestions, create a default
        if (questions.isEmpty()) {
            questions.add(content)
        }

        return questions
    }

    private fun determineTaskType(content: String): TaskType {
        return when {
            content.contains(Regex("research|information|find|tell me about|what is", RegexOption.IGNORE_CASE)) ->
                TaskType.RESEARCH
            content.contains(Regex("analyze|evaluate|reason|logic|solve|think|why", RegexOption.IGNORE_CASE)) ->
                TaskType.REASONING
            content.contains(Regex("create|write|generate|design|story|poem|essay", RegexOption.IGNORE_CASE)) ->
                TaskType.CREATIVE
            content.contains(Regex("code|program|function|algorithm|implement", RegexOption.IGNORE_CASE)) ->
                TaskType.CODE
            content.contains(Regex("review|critique|evaluate|assess|check", RegexOption.IGNORE_CASE)) ->
                TaskType.CRITIC
            else ->
                TaskType.GENERAL
        }
    }

    private fun needsCreativeTask(content: String): Boolean {
        val creativeKeywords = listOf(
            "create", "write", "generate", "design", "story", "poem", "essay",
            "creative", "innovative", "novel", "original", "artistic"
        )

        return creativeKeywords.any { content.contains(it, ignoreCase = true) }
    }

    private fun createSubRequest(originalRequest: Any, subContent: String): Any {
        return when (originalRequest) {
            is ChatRequest -> {
                val messages = originalRequest.messages.toMutableList()
                // Replace the last user message with the sub-content
                val lastUserMessageIndex = messages.indexOfLast { it.role == "user" }
                if (lastUserMessageIndex != -1) {
                    messages[lastUserMessageIndex] = messages[lastUserMessageIndex].copy(content = subContent)
                }
                originalRequest.copy(messages = messages)
            }
            is GenerateRequest -> {
                originalRequest.copy(prompt = subContent)
            }
            else -> originalRequest
        }
    }
}

// Supporting data classes

/**
 * Represents a decomposition strategy for task handling
 */
enum class DecompositionStrategy {
    NONE,           // No decomposition, handle as a single task
    SEQUENTIAL,     // Break into sequential steps
    PARALLEL,       // Break into parallel independent tasks
    SPECIALIZED_AGENTS  // Use specialized agent roles
}

/**
 * Represents a task type for specialized handling
 */
enum class TaskType {
    GENERAL,    // Generic task
    RESEARCH,   // Information gathering
    REASONING,  // Logical analysis
    CREATIVE,   // Creative content generation
    CODE,       // Programming and technical tasks
    CRITIC,     // Review and evaluate
    SYNTHESIS   // Combine and synthesize results
}

/**
 * Represents the status of a task
 */
enum class TaskStatus {
    PENDING,    // Not started
    RUNNING,    // Currently executing
    COMPLETED,  // Successfully completed
    FAILED      // Failed to complete
}

/**
 * Represents a single task within a workflow
 */
data class Task(
    val id: String,                  // Unique task identifier
    val type: TaskType,              // Type of task
    val content: String,             // Task content/description
    val dependencies: Set<String>,   // IDs of tasks this depends on
    val status: TaskStatus,          // Current status
    val originalRequest: Any = JsonObject(),
    val metadata: Any = JsonObject(),// Modified version of the original request
    val deadline: Long,              // Timestamp when task should be completed
    val result: JsonObject? = null,  // Task result (if completed)
    val startedAt: Long? = null,     // When the task started
    val completedAt: Long? = null    // When the task was completed
)

/**
 * Represents a complete workflow of related tasks
 */
data class TaskWorkflow(
    val id: String,                          // Unique workflow identifier
    val tasks: List<Task>,                   // List of tasks in this workflow
    val decompositionStrategy: DecompositionStrategy, // Strategy used
    val createdAt: Long,                     // When the workflow was created
    val originalRequest: Any,                // The original full request
    val completedAt: Long? = null            // When all tasks were completed
)
