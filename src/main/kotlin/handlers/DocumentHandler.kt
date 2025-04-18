package com.dallaslabs.handlers

import com.dallaslabs.models.*
import com.dallaslabs.services.*
import com.dallaslabs.tracking.FlowTracker
import com.dallaslabs.utils.Queue
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

/**
 * Enhanced DocumentHandler capable of processing multiple documents and creating
 * coherent syntheses based on document relationships and content.
 */
class DocumentHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val nodes: List<Node>,
    private val loadBalancer: LoadBalancerService,
    private val logService: LogService,
    private val performanceOptimizationService: PerformanceOptimizationService,
    private val taskDecompositionService: TaskDecompositionService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val webClient = WebClient.create(
        vertx, WebClientOptions()
            .setConnectTimeout(30000)
            .setIdleTimeout(120000)
            .setMaxPoolSize(20) // Increased for multiple concurrent requests
    )

    private val uploadsDir = "uploads"
    private val documentParser = DocumentParser(vertx)
    private val maxDirectProcessingSize = 8000
    private val chunkSize = 4000
    private val maxChunkOverlap = 200

    // Maximum number of documents to process directly
    private val maxDirectDocuments = 3

    // Maximum number of documents to process on a single node
    private val maxNodeDocuments = 5

    // Threshold for number of documents that triggers distributed processing
    private val distributedDocumentThreshold = 4

    init {
        val dir = File(uploadsDir)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info { "Created uploads directory: ${dir.absolutePath}" }
        }
    }

    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()
        logger.info { "Document processing request received (requestId: $requestId)" }

        // Start tracking flow
        FlowTracker.startFlow(
            requestId, mapOf(
                "endpoint" to "/api/document",
                "method" to "POST",
                "type" to "document_request"
            )
        )

        // Update state to RECEIVED
        FlowTracker.updateState(requestId, FlowTracker.FlowState.RECEIVED)

        val fileUploads = ctx.fileUploads()
        if (fileUploads.isEmpty()) {
            FlowTracker.recordError(requestId, "validation_error", "No document files provided")
            ctx.response().setStatusCode(400).end(
                JsonObject.mapFrom(ApiResponse.error<Nothing>("No document files provided")).encode()
            )
            return
        }

        val prompt =
            ctx.request().getFormAttribute("prompt") ?: "Analyze these documents and provide a comprehensive summary"
        val forceStrategy = ctx.request().getFormAttribute("strategy")

        // Update state with document count
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                "prompt" to prompt,
                "documentCount" to fileUploads.size,
                "forcedStrategy" to (forceStrategy ?: "auto")
            )
        )

        launch {
            try {
                // Log the incoming request
                logService.log(
                    "info", "Multi-document processing request received", mapOf(
                        "prompt" to prompt,
                        "documentCount" to fileUploads.size,
                        "requestId" to requestId
                    )
                )

                // Process all documents
                val documentResults = processMultipleDocuments(fileUploads, prompt, requestId)

                // Create synthesized response
                val synthesizedResponse = synthesizeResults(documentResults, prompt, requestId)

                // Update state to COMPLETED
                FlowTracker.updateState(
                    requestId, FlowTracker.FlowState.COMPLETED, mapOf(
                        "documentCount" to fileUploads.size,
                        "synthesisLength" to synthesizedResponse.content.length,
                        "statusCode" to 200
                    )
                )

                // Clean up temporary files
                fileUploads.forEach { upload ->
                    try {
                        vertx.fileSystem().delete(upload.uploadedFileName()).coAwait()
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to delete temporary file: ${upload.uploadedFileName()}" }
                    }
                }

                // Return response to client
                val response = JsonObject()
                    .put("content", synthesizedResponse.content)
                    .put(
                        "metadata", synthesizedResponse.metadata
                            .put("documentCount", fileUploads.size)
                            .put("processingTimeMs", synthesizedResponse.processingTimeMs)
                    )

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(
                        JsonObject.mapFrom(
                            ApiResponse.success(
                                data = response,
                                message = "Documents processed successfully"
                            )
                        ).encode()
                    )

            } catch (e: Exception) {
                logger.error(e) { "Failed to process document request (requestId: $requestId)" }

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "processing_error", e.message ?: "Unknown error")

                // Clean up temporary files
                fileUploads.forEach { upload ->
                    try {
                        vertx.fileSystem().delete(upload.uploadedFileName())
                    } catch (deleteError: Exception) {
                        logger.error(deleteError) { "Failed to delete temporary file: ${upload.uploadedFileName()}" }
                    }
                }

                // Format the error response
                val apiResponse = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(apiResponse).encode())
            }
        }
    }

    /**
     * Process multiple documents and return results for each
     */
    private suspend fun processMultipleDocuments(
        fileUploads: List<io.vertx.ext.web.FileUpload>,
        prompt: String,
        requestId: String
    ): List<DocumentResult> {
        // First, analyze all documents to determine the processing strategy
        val documentInfos = analyzeDocuments(fileUploads, requestId)

        // Determine the global processing strategy
        val globalStrategy = determineGlobalStrategy(documentInfos)

        logger.info { "Selected global strategy: $globalStrategy for ${documentInfos.size} documents (requestId: $requestId)" }

        // Update flow state with the strategy
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                "strategy" to globalStrategy.name,
                "documentCount" to documentInfos.size
            )
        )

        // Process documents according to the strategy
        return when (globalStrategy) {
            ProcessingStrategy.DIRECT -> {
                // Process each document directly
                documentInfos.mapIndexed { index, docInfo ->
                    processDocument(
                        docInfo,
                        "$prompt (Document ${index + 1} of ${documentInfos.size})",
                        "$requestId-doc-$index"
                    )
                }
            }

            ProcessingStrategy.NODE_BACKEND -> {
                // Send all documents to a Node.js backend
                processDocumentsWithNodeBackend(documentInfos, prompt, requestId)
            }

            ProcessingStrategy.DISTRIBUTED -> {
                // Distribute documents across multiple nodes
                processDocumentsDistributed(documentInfos, prompt, requestId)
            }

            ProcessingStrategy.COMBINED_CHUNKING -> {
                // Process documents by combining and chunking
                processCombinedChunking(documentInfos, prompt, requestId)
            }
        }
    }

    /**
     * Analyze all documents to extract metadata and text
     */
    private suspend fun analyzeDocuments(
        fileUploads: List<io.vertx.ext.web.FileUpload>,
        requestId: String
    ): List<DocumentInfo> {
        // Create a list to hold document information
        val documentInfos = mutableListOf<DocumentInfo>()

        // Update flow state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.ANALYZING, mapOf(
                "documentCount" to fileUploads.size,
                "stage" to "analyzing_documents"
            )
        )

        // Process documents in parallel using coroutines
        val results = fileUploads.mapIndexed { index, fileUpload ->
            async {
                try {
                    // Extract text from document
                    val extractedText = documentParser.extractText(
                        fileUpload.uploadedFileName(),
                        fileUpload.contentType()
                    )

                    // Create document info
                    DocumentInfo(
                        id = "$requestId-doc-$index",
                        filename = fileUpload.fileName(),
                        contentType = fileUpload.contentType(),
                        text = extractedText,
                        size = fileUpload.size(),
                        uploadPath = fileUpload.uploadedFileName(),
                        metadata = JsonObject()
                            .put("originalName", fileUpload.fileName())
                            .put("contentType", fileUpload.contentType())
                            .put("size", fileUpload.size())
                            .put("charCount", extractedText.length)
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to analyze document ${fileUpload.fileName()} (requestId: $requestId)" }
                    throw e
                }
            }
        }.awaitAll()

        // Add results to the list
        documentInfos.addAll(results)

        // Log analysis results
        logger.info { "Analyzed ${documentInfos.size} documents with total text length: ${documentInfos.sumOf { it.text.length }} characters (requestId: $requestId)" }

        return documentInfos
    }

    /**
     * Determine the global processing strategy for multiple documents
     */
    private fun determineGlobalStrategy(documents: List<DocumentInfo>): ProcessingStrategy {
        // Calculate total size and document count statistics
        val totalTextLength = documents.sumOf { it.text.length }
        val maxDocumentSize = documents.maxOfOrNull { it.text.length } ?: 0
        val documentCount = documents.size

        return when {
            // Small number of documents with small texts - process directly
            documentCount <= maxDirectDocuments && totalTextLength < maxDirectProcessingSize * documentCount -> {
                ProcessingStrategy.DIRECT
            }

            // Medium number of documents or medium total size - process on Node backend
            documentCount <= maxNodeDocuments && totalTextLength < 200000 -> {
                ProcessingStrategy.NODE_BACKEND
            }

            // Larger number of documents - distribute across nodes
            documentCount >= distributedDocumentThreshold -> {
                ProcessingStrategy.DISTRIBUTED
            }

            // Very large documents - combine and chunk
            else -> {
                ProcessingStrategy.COMBINED_CHUNKING
            }
        }
    }

    /**
     * Process a single document and return the result
     */
    private suspend fun processDocument(
        docInfo: DocumentInfo,
        prompt: String,
        requestId: String
    ): DocumentResult {
        logger.info { "Processing document ${docInfo.filename} (${docInfo.text.length} chars) with requestId: $requestId" }

        // Find appropriate models
        val textModels = findModelsWithLargeContext()
        if (textModels.isEmpty()) {
            throw IllegalStateException("No suitable models available for document processing")
        }

        // Select best model based on document properties
        val selectedModel = selectBestTextModel(textModels, docInfo)

        // Get optimized parameters
        val optimizedParams = performanceOptimizationService.getOptimizedParameters(
            selectedModel, "document_processing"
        )

        // Select the best node for this model
        val nodeName = loadBalancer.selectBestNodeForModel(selectedModel)
            ?: throw IllegalStateException("No suitable node found for model $selectedModel")

        // Create the combined prompt
        val combinedPrompt = """
            $prompt
            
            Document: ${docInfo.filename}
            
            Content:
            ${docInfo.text}
        """.trimIndent()

        // Create chat request
        val chatRequest = JsonObject()
            .put("model", selectedModel)
            .put("node", nodeName)
            .put(
                "messages", JsonArray().add(
                    JsonObject()
                        .put("role", "user")
                        .put("content", combinedPrompt)
                )
            )

        // Add optimized parameters
        optimizedParams.map.forEach { (key, value) ->
            if (!chatRequest.containsKey(key)) {
                chatRequest.put(key, value)
            }
        }

        // Start tracking performance
        val startTime = System.currentTimeMillis()

        // Update state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                "documentName" to docInfo.filename,
                "model" to selectedModel,
                "node" to nodeName,
                "textLength" to docInfo.text.length
            )
        )

        // Use the queue for request management
        return try {
            val result = queue.add {
                val response = webClient.post("/api/chat")
                    .putHeader("X-Request-ID", requestId)
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(chatRequest)
                    .coAwait()

                if (response.statusCode() != 200) {
                    throw IllegalStateException("Failed to process document: ${response.bodyAsString()}")
                }

                // Extract the response content
                val responseJson = response.bodyAsJsonObject()
                val content = responseJson.getJsonObject("message")?.getString("content") ?: ""
                val usage = responseJson.getJsonObject("usage") ?: JsonObject()

                response.bodyAsJsonObject()
            }.coAwait()

            // Extract the result and return a DocumentResult
            val processingTime = System.currentTimeMillis() - startTime
            val message = result.getJsonObject("message") ?: JsonObject()
            val content = message.getString("content") ?: ""

            DocumentResult(
                documentId = docInfo.id,
                filename = docInfo.filename,
                content = content,
                processingTimeMs = processingTime,
                metadata = JsonObject()
                    .put("model", selectedModel)
                    .put("node", nodeName)
                    .put("processingTimeMs", processingTime)
                    .put("originalSize", docInfo.size)
                    .put("originalType", docInfo.contentType)
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to process document ${docInfo.filename} (requestId: $requestId)" }
            throw e
        }
    }

    /**
     * Process multiple documents using a Node.js backend
     */
    private suspend fun processDocumentsWithNodeBackend(
        documents: List<DocumentInfo>,
        prompt: String,
        requestId: String
    ): List<DocumentResult> {
        // Select a suitable node backend
        val nodeName = selectBackendForDocumentProcessing()
        val node = nodes.find { it.name == nodeName }
            ?: throw IllegalStateException("Node $nodeName not found")

        logger.info { "Processing ${documents.size} documents on node $nodeName (requestId: $requestId)" }

        // Update flow state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.NODE_SELECTION, mapOf(
                "selectedNode" to nodeName,
                "nodeType" to node.type,
                "documentCount" to documents.size,
                "strategy" to "NODE_BACKEND"
            )
        )

        // Process each document on the backend
        return documents.mapIndexed { index, docInfo ->
            // Process this document
            val startTime = System.currentTimeMillis()

            try {
                // Create a temporary file with document content (if needed)
                // For this example, we'll just send the extracted text

                // Create processing request for the backend
                val processingRequest = JsonObject()
                    .put("prompt", "$prompt (Document ${index + 1} of ${documents.size})")
                    .put("content", docInfo.text)
                    .put("model", selectModelForDocumentType(docInfo.contentType))
                    .put("node", nodeName)
                    .put("documentName", docInfo.filename)

                // Send the processing request
                val response = webClient.post(node.port, node.host, "/document/process-chunk")
                    .putHeader("X-Request-ID", "$requestId-doc-$index")
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(processingRequest)
                    .coAwait()

                if (response.statusCode() != 200) {
                    throw IllegalStateException("Failed to process document: ${response.bodyAsString()}")
                }

                val result = response.bodyAsJsonObject()
                    .getJsonObject("data") ?: JsonObject()

                val content = result.getString("content") ?: ""
                val metadata = result.getJsonObject("metadata") ?: JsonObject()

                DocumentResult(
                    documentId = docInfo.id,
                    filename = docInfo.filename,
                    content = content,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    metadata = metadata.put("strategy", "NODE_BACKEND")
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to process document ${docInfo.filename} on node $nodeName (requestId: $requestId)" }
                throw e
            }
        }
    }

    /**
     * Process documents by distributing across multiple nodes
     */
    private suspend fun processDocumentsDistributed(
        documents: List<DocumentInfo>,
        prompt: String,
        requestId: String
    ): List<DocumentResult> {
        logger.info { "Processing ${documents.size} documents with distributed strategy (requestId: $requestId)" }

        // Update flow state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                "strategy" to "DISTRIBUTED",
                "documentCount" to documents.size
            )
        )

        // Create a workflow
        val workflow = taskDecompositionService.createMultiDocumentWorkflow(
            requestId,
            documents,
            prompt,
            "distributed_document_processing"
        )

        // Monitor workflow until all document processing tasks are complete
        var results = listOf<DocumentResult>()
        var isComplete = false
        var retryCount = 0

        while (!isComplete && retryCount < 30) {
            // Get workflow status
            val status = taskDecompositionService.getWorkflow(workflow.id)

            // Check if document processing tasks are complete
            if (status?.hasStatus("COMPLETED") == true ||
                status?.hasStatus("PARTIALLY_COMPLETED") == true
            ) {

                // Get results from completed tasks
                val taskResults = taskDecompositionService.getFinalResults(workflow.id)

                // Convert task results to DocumentResults
                results = documents.mapIndexed { index, docInfo ->
                    val taskResult = taskResults.getJsonObject(docInfo.id) ?: JsonObject()

                    DocumentResult(
                        documentId = docInfo.id,
                        filename = docInfo.filename,
                        content = taskResult.getString("content") ?: "No content available",
                        processingTimeMs = taskResult.getLong("processingTimeMs") ?: 0,
                        metadata = taskResult.getJsonObject("metadata") ?: JsonObject()
                    )
                }

                isComplete = true
            } else {
                // Wait before checking again
                delay(2000)
                retryCount++
            }
        }

        if (!isComplete) {
            throw IllegalStateException("Workflow did not complete in time: ${workflow.id}")
        }

        return results
    }

    /**
     * Process documents by combining and chunking
     */
    private suspend fun processCombinedChunking(
        documents: List<DocumentInfo>,
        prompt: String,
        requestId: String
    ): List<DocumentResult> {
        logger.info { "Processing ${documents.size} documents with combined chunking (requestId: $requestId)" }

        // Update flow state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                "strategy" to "COMBINED_CHUNKING",
                "documentCount" to documents.size
            )
        )

        // First process each document individually to get summaries
        val summaryResults = documents.mapIndexed { index, docInfo ->
            // Create a summary-focused prompt
            val summaryPrompt = """
                Provide a concise but comprehensive summary of the following document.
                
                Document: ${docInfo.filename}
                Document ${index + 1} of ${documents.size}
                
                Content:
                ${docInfo.text}
            """.trimIndent()

            // Process with a summary-optimized approach
            processDocument(docInfo, summaryPrompt, "$requestId-summary-$index")
        }

        // Create full results by combining original document info with summaries
        return documents.mapIndexed { index, docInfo ->
            DocumentResult(
                documentId = docInfo.id,
                filename = docInfo.filename,
                content = summaryResults[index].content,
                processingTimeMs = summaryResults[index].processingTimeMs,
                metadata = summaryResults[index].metadata.put("strategy", "COMBINED_CHUNKING"),
                originalText = docInfo.text
            )
        }
    }

    /**
     * Synthesize results from multiple documents into a coherent response
     */
    private suspend fun synthesizeResults(
        documentResults: List<DocumentResult>,
        prompt: String,
        requestId: String
    ): SynthesisResult {
        logger.info { "Synthesizing results from ${documentResults.size} documents (requestId: $requestId)" }

        // Update flow state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.SYNTHESIZING, mapOf(
                "documentCount" to documentResults.size
            )
        )

        // Find models with strong reasoning capabilities
        val textModels = findModelsWithLargeContext().filter { model ->
            val modelId = model.id.lowercase()
            modelId.contains("claude3:opus") ||
                    modelId.contains("gpt-4") ||
                    modelId.contains("llama3:70b")
        }

        if (textModels.isEmpty()) {
            throw IllegalStateException("No suitable models available for synthesis")
        }

        // Select best model for synthesis
        val selectedModel = textModels.first().id
        val nodeName = loadBalancer.selectBestNodeForModel(selectedModel)
            ?: throw IllegalStateException("No suitable node found for model $selectedModel")

        // Create synthesis prompt
        val synthesisPrompt = createSynthesisPrompt(documentResults, prompt)

        // Create chat request
        val chatRequest = JsonObject()
            .put("model", selectedModel)
            .put("node", nodeName)
            .put(
                "messages", JsonArray().add(
                    JsonObject()
                        .put("role", "user")
                        .put("content", synthesisPrompt)
                )
            )

        // Start tracking performance
        val startTime = System.currentTimeMillis()

        // Update state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.EXECUTING, mapOf(
                "stage" to "synthesis",
                "model" to selectedModel,
                "node" to nodeName
            )
        )

        // Use the queue for request management
        try {
            val result = queue.add {
                val response = webClient.post("/api/chat")
                    .putHeader("X-Request-ID", "$requestId-synthesis")
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(chatRequest)
                    .coAwait()

                if (response.statusCode() != 200) {
                    throw IllegalStateException("Failed to synthesize results: ${response.bodyAsString()}")
                }

                response.bodyAsJsonObject()
            }.coAwait()

            // Extract the result and return a SynthesisResult
            val processingTime = System.currentTimeMillis() - startTime
            val message = result.getJsonObject("message") ?: JsonObject()
            val content = message.getString("content") ?: ""

            return SynthesisResult(
                content = content,
                processingTimeMs = processingTime,
                metadata = JsonObject()
                    .put("model", selectedModel)
                    .put("node", nodeName)
                    .put("documentCount", documentResults.size)
                    .put("synthesisTimeMs", processingTime)
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to synthesize results (requestId: $requestId)" }
            throw e
        }
    }

    /**
     * Create a prompt for synthesis of multiple document results
     */
    private fun createSynthesisPrompt(
        documentResults: List<DocumentResult>,
        originalPrompt: String
    ): String {
        val sb = StringBuilder()

        sb.append(
            """
            You are synthesizing information from multiple documents to create a coherent and comprehensive response.
            
            Original request: $originalPrompt
            
            The following documents have been analyzed:
        """.trimIndent()
        )

        // Add each document's information
        documentResults.forEachIndexed { index, result ->
            sb.append("\n\n")
            sb.append("=== DOCUMENT ${index + 1}: ${result.filename} ===\n")
            sb.append(result.content)
        }

        sb.append(
            """
            
            INSTRUCTIONS:
            1. Provide a comprehensive analysis that addresses the original request
            2. Synthesize information from all documents into a coherent response
            3. Highlight important relationships or contradictions between documents
            4. Ensure your response is well-structured and easy to understand
            5. Include specific references to documents when appropriate
        """.trimIndent()
        )

        return sb.toString()
    }

    /**
     * Find models with large context windows
     */
    private fun findModelsWithLargeContext(): List<ModelInfo> {
        val allModels = modelRegistry.getAllModels()

        // Filter for models with large context windows
        return allModels.filter { model ->
            val modelId = model.id.lowercase()
            modelId.contains("llama3:70b") ||
                    modelId.contains("claude3:opus") ||
                    modelId.contains("gpt-4") ||
                    modelId.contains("mistral-large") ||
                    modelId.contains("claude3:sonnet")
        }
    }

    /**
     * Select the best model based on document properties
     */
    private fun selectBestTextModel(models: List<ModelInfo>, document: DocumentInfo): String {
        if (models.isEmpty()) {
            throw IllegalStateException("No suitable models available")
        }

        // Determine document complexity
        val isCodeDocument = document.contentType.contains("text/x-") ||
                document.filename.endsWith(".java") ||
                document.filename.endsWith(".py") ||
                document.filename.endsWith(".js") ||
                document.filename.endsWith(".ts") ||
                document.filename.endsWith(".kt") ||
                document.filename.endsWith(".c") ||
                document.filename.endsWith(".cpp")

        val isPdfDocument = document.contentType.contains("application/pdf")
        val isLargeDocument = document.text.length > 10000

        // Model preferences based on document type
        val preferredModels = when {
            isCodeDocument -> listOf("claude3:opus", "gpt-4", "llama3:70b")
            isPdfDocument -> listOf("gpt-4", "claude3:opus", "llama3:70b")
            isLargeDocument -> listOf("claude3:opus", "gpt-4", "llama3:70b")
            else -> listOf("llama3:70b", "claude3:sonnet", "mistral-large")
        }

        // Find the first available model in priority order
        for (modelPrefix in preferredModels) {
            val matchingModel = models.find {
                it.id.lowercase().contains(modelPrefix.lowercase())
            }

            if (matchingModel != null) {
                return matchingModel.id
            }
        }

        // If no priority match, return any available model
        return models.first().id
    }

    /**
     * Select the most appropriate backend node for document processing
     */
    private suspend fun selectBackendForDocumentProcessing(): String {
        // Get available nodes with document processing capability
        val availableNodes = nodes.filter { node ->
            node.capabilities.contains("parallel")
        }

        if (availableNodes.isEmpty()) {
            throw IllegalStateException("No suitable nodes available for document processing")
        }

        // Use load balancer to select the least busy node
        return loadBalancer.selectLeastBusyNode(availableNodes.map { it.name })
            ?: availableNodes.first().name
    }

    /**
     * Select the most appropriate model for the document type
     */
    private fun selectModelForDocumentType(contentType: String): String {
        // Get available models from registry
        val availableModels = modelRegistry.getAllModels()

        // Select model based on content type
        val modelId = when {
            contentType.contains("application/pdf") -> {
                availableModels.find { it.id.contains("llama3") || it.id.contains("claude") }?.id
                    ?: "llama3:70b"
            }

            contentType.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document") -> {
                availableModels.find { it.id.contains("claude") || it.id.contains("gpt") }?.id
                    ?: "llama3:70b"
            }

            contentType.contains("text/x-") || contentType.contains("application/javascript") -> {
                availableModels.find { it.id.contains("claude3:opus") || it.id.contains("gpt-4") }?.id
                    ?: "llama3:70b"
            }

            contentType.contains("application/json") || contentType.contains("application/xml") -> {
                availableModels.find { it.id.contains("llama3") || it.id.contains("gpt") }?.id
                    ?: "llama3:70b"
            }

            contentType.contains("text/csv") || contentType.contains("application/vnd.ms-excel") -> {
                availableModels.find { it.id.contains("gpt-4") || it.id.contains("claude3:opus") }?.id
                    ?: "llama3:70b"
            }

            else -> "llama3.2:latest"
        }

        return modelId
    }

    /**
     * Process very large documents by distributing chunks across multiple nodes
     */
    private suspend fun handleLargeDocument(
        ctx: RoutingContext,
        documentText: String,
        prompt: String,
        requestId: String
    ): DocumentResult {
        // Create a workflow using the task decomposition service
        val workflow = taskDecompositionService.createDocumentWorkflow(
            requestId,
            documentText,
            prompt,
            "chunked_document_processing"
        )

        // Update flow state
        FlowTracker.updateState(
            requestId, FlowTracker.FlowState.TASK_DECOMPOSED, mapOf(
                "workflowId" to workflow.id,
                "taskCount" to workflow.tasks.size
            )
        )

        logger.info { "Created document workflow for large document (workflowId: ${workflow.id}, taskCount: ${workflow.tasks.size})" }

        // Now monitor the workflow until completion
        var isComplete = false
        var retryCount = 0

        while (!isComplete && retryCount < 30) {
            // Check if all tasks are complete
            if (taskDecompositionService.isWorkflowComplete(workflow.id)) {
                isComplete = true
            } else {
                // Process next executable tasks
                val executableTasks = taskDecompositionService.getNextExecutableTasks(workflow.id)

                for (task in executableTasks) {
                    // Update task status to running
                    taskDecompositionService.updateTaskStatus(workflow.id, task.id, TaskStatus.RUNNING)

                    // Process the task
                    launch {
                        try {
                            val taskResult = processDocumentTask(task, requestId)

                            // Update task status to completed
                            taskDecompositionService.updateTaskStatus(
                                workflow.id,
                                task.id,
                                TaskStatus.COMPLETED,
                                taskResult
                            )
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to process task ${task.id}" }

                            // Update task status to failed
                            taskDecompositionService.updateTaskStatus(
                                workflow.id,
                                task.id,
                                TaskStatus.FAILED,
                                JsonObject().put("error", e.message)
                            )
                        }
                    }
                }

                // Wait before checking again
                delay(2000)
                retryCount++
            }
        }

        if (!isComplete) {
            throw IllegalStateException("Workflow did not complete in time: ${workflow.id}")
        }

        // Get the final result
        val finalResult = taskDecompositionService.getFinalResult(workflow.id)
            ?: throw IllegalStateException("No final result available for workflow: ${workflow.id}")

        return DocumentResult(
            documentId = requestId,
            filename = "large-document.txt", // Placeholder filename
            content = finalResult.getString("content") ?: "",
            processingTimeMs = workflow.completedAt?.minus(workflow.createdAt) ?: 0,
            metadata = JsonObject()
                .put("workflowId", workflow.id)
                .put("taskCount", workflow.tasks.size)
                .put("strategy", "chunked_document_processing")
        )
    }

    /**
     * Process an individual document task from a workflow
     */
    private suspend fun processDocumentTask(task: Task, requestId: String): JsonObject {
        // Extract task information
        val originalRequest = task.originalRequest as JsonObject
        val chunkContent = originalRequest.getString("content") ?: ""
        val taskPrompt = originalRequest.getString("prompt") ?: ""
        val chunkIndex = originalRequest.getInteger("chunkIndex", 0)
        val totalChunks = originalRequest.getInteger("totalChunks", 1)

        // Find an appropriate model
        val textModels = findModelsWithLargeContext()
        if (textModels.isEmpty()) {
            throw IllegalStateException("No suitable models available for document processing")
        }

        // Select a model
        val selectedModel = textModels.first().id

        // Select a node
        val nodeName = loadBalancer.selectBestNodeForModel(selectedModel)
            ?: throw IllegalStateException("No suitable node found for model $selectedModel")

        // Prepare the request
        val processingRequest = JsonObject()
            .put("chunkId", task.id)
            .put("content", chunkContent)
            .put("index", chunkIndex)
            .put("totalChunks", totalChunks)
            .put("model", selectedModel)
            .put("prompt", taskPrompt)

        // Get the node
        val node = nodes.find { it.name == nodeName }
            ?: throw IllegalStateException("Node $nodeName not found")

        // Make the request
        val startTime = System.currentTimeMillis()

        val response = webClient.post(node.port, node.host, "/document/process-chunk")
            .putHeader("X-Request-ID", "${requestId}-chunk-${chunkIndex}")
            .putHeader("Content-Type", "application/json")
            .sendJsonObject(processingRequest)
            .coAwait()

        if (response.statusCode() != 200) {
            throw IllegalStateException("Failed to process document chunk: ${response.bodyAsString()}")
        }

        val result = response.bodyAsJsonObject()
            .getJsonObject("data") ?: JsonObject()

        return JsonObject()
            .put("content", result.getString("content") ?: "")
            .put("processingTimeMs", System.currentTimeMillis() - startTime)
            .put("model", selectedModel)
            .put("node", nodeName)
            .put("chunkIndex", chunkIndex)
            .put("totalChunks", totalChunks)
    }

    /**
     * Extension functions for TaskWorkflow to make status checks easier
     */

    /**
     * Extension function to check if a workflow has a specific status
     */
    fun TaskWorkflow.hasStatus(status: String): Boolean {
        // Count completed tasks
        val completedCount = this.tasks.count { it.status == TaskStatus.COMPLETED }
        val failedCount = this.tasks.count { it.status == TaskStatus.FAILED }
        val totalCount = this.tasks.size

        return when (status.uppercase()) {
            "COMPLETED" -> completedCount == totalCount
            "PARTIALLY_COMPLETED" -> completedCount > 0 && completedCount < totalCount
            "FAILED" -> failedCount == totalCount
            "PARTIALLY_FAILED" -> failedCount > 0 && failedCount < totalCount
            "IN_PROGRESS" -> this.tasks.any { it.status == TaskStatus.RUNNING }
            "PENDING" -> this.tasks.all { it.status == TaskStatus.PENDING }
            else -> false
        }
    }

    /**
     * Extension function to get the overall workflow status as a string
     */
    fun TaskWorkflow.getStatus(): String {
        val completedCount = this.tasks.count { it.status == TaskStatus.COMPLETED }
        val failedCount = this.tasks.count { it.status == TaskStatus.FAILED }
        val runningCount = this.tasks.count { it.status == TaskStatus.RUNNING }
        val pendingCount = this.tasks.count { it.status == TaskStatus.PENDING }
        val totalCount = this.tasks.size

        return when {
            completedCount == totalCount -> "COMPLETED"
            failedCount == totalCount -> "FAILED"
            completedCount + failedCount == totalCount -> "PARTIALLY_COMPLETED"
            runningCount > 0 -> "IN_PROGRESS"
            pendingCount == totalCount -> "PENDING"
            else -> "MIXED"
        }
    }

    /**
     * Add these helper methods to TaskDecompositionService class
     */

    /**
     * Convert a TaskWorkflow to a JsonObject for easier API responses
     */
    fun TaskDecompositionService.workflowToJson(workflow: TaskWorkflow): JsonObject {
        val json = JsonObject()
            .put("id", workflow.id)
            .put("strategy", workflow.decompositionStrategy.name)
            .put("createdAt", workflow.createdAt)
            .put("status", workflow.getStatus())
            .put("taskCount", workflow.tasks.size)
            .put("completedTasks", workflow.tasks.count { it.status == TaskStatus.COMPLETED })
            .put("failedTasks", workflow.tasks.count { it.status == TaskStatus.FAILED })
            .put("pendingTasks", workflow.tasks.count { it.status == TaskStatus.PENDING })
            .put("runningTasks", workflow.tasks.count { it.status == TaskStatus.RUNNING })

        // Add completion time if available
        workflow.completedAt?.let { json.put("completedAt", it) }

        return json
    }
}

class DocumentParser(private val vertx: Vertx) {
    private val executor = Executors.newFixedThreadPool(4)

    suspend fun extractText(filePath: String, contentType: String): String = withContext(Dispatchers.IO) {
        when {
            contentType.contains("text/plain") -> {
                vertx.fileSystem().readFileBlocking(filePath).toString(Charsets.UTF_8)
            }

            contentType.contains("application/pdf") -> {
                extractPdfText(filePath)
            }

            contentType.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document") -> {
                extractDocxText(filePath)
            }

            contentType.contains("application/json") -> {
                vertx.fileSystem().readFileBlocking(filePath).toString(Charsets.UTF_8)
            }
            // Add more document types as needed
            else -> {
                throw UnsupportedOperationException("Unsupported document type: $contentType")
            }
        }
    }

    private fun extractPdfText(filePath: String): String {
        return CompletableFuture.supplyAsync({
            val pdfDocument = PDDocument.load(File(filePath))
            try {
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.getText(pdfDocument)
            } finally {
                pdfDocument.close()
            }
        }, executor).get()
    }

    private fun extractDocxText(filePath: String): String {
        return CompletableFuture.supplyAsync({
            val docxFile = File(filePath)
            val docx = XWPFDocument(FileInputStream(docxFile))
            try {
                val result = StringBuilder()

                // Extract paragraphs
                for (paragraph in docx.paragraphs) {
                    result.append(paragraph.text).append("\n")
                }

                // Extract tables
                for (table in docx.tables) {
                    for (row in table.rows) {
                        for (cell in row.tableCells) {
                            result.append(cell.text).append("\t")
                        }
                        result.append("\n")
                    }
                }

                result.toString()
            } finally {
                docx.close()
            }
        }, executor).get()
    }
}
