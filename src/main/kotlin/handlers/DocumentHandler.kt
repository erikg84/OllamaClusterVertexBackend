package com.dallaslabs.handlers

import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.ModelInfo
import com.dallaslabs.models.Node
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

class DocumentHandler(
    private val vertx: Vertx,
    private val queue: Queue,
    private val modelRegistry: ModelRegistryService,
    private val nodes: List<Node>,
    private val performanceTracker: PerformanceTrackerService,
    private val loadBalancer: LoadBalancerService,
    private val logService: LogService,
    private val performanceOptimizationService: PerformanceOptimizationService
) : CoroutineScope by CoroutineScope(vertx.dispatcher()) {

    private val webClient = WebClient.create(vertx, WebClientOptions()
        .setConnectTimeout(30000)
        .setIdleTimeout(60000)
    )

    private val uploadsDir = "uploads"
    private val documentParser = DocumentParser(vertx)

    init {
        // Create uploads directory if it doesn't exist
        val dir = File(uploadsDir)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info { "Created uploads directory: ${dir.absolutePath}" }
        }
    }

    fun handle(ctx: RoutingContext) {
        val requestId = ctx.get<String>("requestId") ?: UUID.randomUUID().toString()
        logger.info { "Document processing request received (requestId: $requestId)" }

        // Start tracking flow with initial metadata
        FlowTracker.startFlow(requestId, mapOf(
            "endpoint" to "/api/document",
            "method" to "POST",
            "type" to "document_request"
        ))

        // Update state to RECEIVED
        FlowTracker.updateState(requestId, FlowTracker.FlowState.RECEIVED)

        val fileUploads = ctx.fileUploads()
        if (fileUploads.isEmpty()) {
            FlowTracker.recordError(requestId, "validation_error", "No document file provided")
            ctx.response().setStatusCode(400).end(
                JsonObject.mapFrom(ApiResponse.error<Nothing>("No document file provided")).encode()
            )
            return
        }

        val documentUpload = fileUploads.first()
        val prompt = ctx.request().getFormAttribute("prompt") ?: "Summarize this document"

        // Update state to ANALYZING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.ANALYZING, mapOf(
            "prompt" to prompt,
            "documentSize" to documentUpload.size(),
            "documentType" to documentUpload.contentType()
        ))

        launch {
            try {
                // Log the incoming request
                logService.log("info", "Document request received", mapOf(
                    "prompt" to prompt,
                    "documentSize" to documentUpload.size(),
                    "contentType" to documentUpload.contentType(),
                    "requestId" to requestId
                ))

                // Extract text from document
                val extractedText = documentParser.extractText(
                    documentUpload.uploadedFileName(),
                    documentUpload.contentType()
                )

                logger.info { "Document text extracted, ${extractedText.length} characters (requestId: $requestId)" }

                // Check if document is too large for context window
                val needsChunking = extractedText.length > 32000 // Approx token limit

                if (needsChunking) {
                    handleLargeDocument(ctx, extractedText, prompt, requestId)
                } else {
                    processSingleDocument(ctx, extractedText, prompt, requestId)
                }

            } catch (e: Exception) {
                logger.error(e) { "Failed to process document request (requestId: $requestId)" }

                // Record error and transition to FAILED state
                FlowTracker.recordError(requestId, "processing_error", e.message ?: "Unknown error")

                // Format the error response
                val apiResponse = ApiResponse.error<Nothing>(e.message ?: "Unknown error")

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(500)
                    .end(JsonObject.mapFrom(apiResponse).encode())

                try {
                    vertx.fileSystem().delete(documentUpload.uploadedFileName())
                } catch (deleteError: Exception) {
                    logger.error(deleteError) { "Failed to delete temporary file: ${documentUpload.uploadedFileName()}" }
                }
            }
        }
    }

    private suspend fun processSingleDocument(
        ctx: RoutingContext,
        documentText: String,
        prompt: String,
        requestId: String
    ) {
        // Find appropriate models for document processing
        val textModels = findModelsWithLargeContext()

        if (textModels.isEmpty()) {
            FlowTracker.recordError(requestId, "model_not_available", "No suitable models available for document processing")
            ctx.response().setStatusCode(503).end(
                JsonObject.mapFrom(ApiResponse.error<Nothing>("No suitable models available")).encode()
            )
            return
        }

        // Select best model based on document size and complexity
        val selectedModel = selectBestTextModel(textModels, documentText, prompt)

        // Select the best node for this model
        val nodeName = loadBalancer.selectBestNodeForModel(selectedModel)
            ?: throw IllegalStateException("No suitable node found for model $selectedModel")

        val nodeFromServer = nodes.find { it.name == nodeName }
            ?: throw IllegalStateException("Node $nodeName not found in configuration")

        // Create the combined prompt
        val combinedPrompt = if (prompt.contains("{document}")) {
            // Allow custom prompt templates
            prompt.replace("{document}", documentText)
        } else {
            // Default prompt structure
            """$prompt
            
            Document content:
            $documentText
            """
        }

        // Create chat request
        val chatRequest = JsonObject()
            .put("model", selectedModel)
            .put("node", nodeName)
            .put("messages", JsonArray().add(
                JsonObject()
                    .put("role", "user")
                    .put("content", combinedPrompt)
            ))
            .put("stream", false) // Non-streaming for simplicity

        // Start tracking performance
        val startTime = System.currentTimeMillis()
        performanceTracker.recordRequestStart(nodeName)

        // Update state to EXECUTING
        FlowTracker.updateState(requestId, FlowTracker.FlowState.EXECUTING, mapOf(
            "executionStartedAt" to startTime,
            "nodeHost" to nodeFromServer.host,
            "nodePort" to nodeFromServer.port,
            "model" to selectedModel
        ))

        // Use the queue for request management and load balancing
        val result = queue.add {
            try {
                val response = webClient.post(nodeFromServer.port, nodeFromServer.host, "/chat")
                    .putHeader("X-Request-ID", requestId)
                    .putHeader("Content-Type", "application/json")
                    .sendJsonObject(chatRequest)
                    .coAwait()

                // Record metrics
                val endTime = System.currentTimeMillis()
                val processingTime = endTime - startTime

                // Record response metrics similar to your vision handler
                // ...

                response
            } catch (e: Exception) {
                loadBalancer.recordFailure(nodeName)
                FlowTracker.recordError(requestId, "request_execution_error", e.message ?: "Unknown error")
                logService.logError("Document processing request failed", e, mapOf(
                    "requestId" to requestId,
                    "modelId" to selectedModel,
                    "nodeName" to nodeName
                ))
                throw e
            }
        }.coAwait()

        // Process the result and respond to client
        // ...
    }

    private suspend fun handleLargeDocument(
        ctx: RoutingContext,
        documentText: String,
        prompt: String,
        requestId: String
    ) {
        // Implementation for large document chunking and processing
        // This could use Task Decomposition or MAESTRO patterns
        // ...
    }

    private fun findModelsWithLargeContext(): List<ModelInfo> {
        val allModels = modelRegistry.getAllModels()

        // Filter for models with large context windows
        return allModels.filter { model ->
            // Prefer models with large context windows
            val modelId = model.id.lowercase()
            modelId.contains("llama3:70b") ||
                    modelId.contains("claude3:opus") ||
                    modelId.contains("gpt-4") ||
                    modelId.contains("mistral-large") ||
                    modelId.contains("claude3:sonnet")
        }
    }

    private fun selectBestTextModel(
        availableModels: List<ModelInfo>,
        documentText: String,
        prompt: String
    ): String {
        // Model selection logic similar to your vision handler
        // ...

        // For now, return the first available model
        return availableModels.first().id
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
