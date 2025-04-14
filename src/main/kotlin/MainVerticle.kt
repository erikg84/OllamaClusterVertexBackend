package com.dallaslabs

import com.dallaslabs.config.ConfigLoader
import com.dallaslabs.handlers.*
import com.dallaslabs.models.ApiResponse
import com.dallaslabs.models.Node
import com.dallaslabs.services.*
import com.dallaslabs.tracking.FlowTracker
import com.dallaslabs.utils.Queue
import io.vertx.core.json.JsonObject
import io.vertx.ext.mongo.MongoClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.coAwait
import mu.KotlinLogging
import java.lang.Runtime

private val logger = KotlinLogging.logger {}

class MainVerticle : CoroutineVerticle() {

    override suspend fun start() {
        logger.info { "Starting LLM Cluster API..." }

        val configLoader = ConfigLoader(vertx)
        val config = configLoader.loadConfig("config.yaml").coAwait()
        logger.info { "Configuration loaded successfully" }

        val serverPort = config.getJsonObject("server").getInteger("port", 3001)
        val concurrency = config.getJsonObject("server").getInteger("concurrency", 5)

        val nodesArray = config.getJsonArray("nodes")
        val nodes = nodesArray.map { nodeJson ->
            val node = nodeJson as JsonObject
            Node(
                name = node.getString("name"),
                host = node.getString("host"),
                port = node.getInteger("port"),
                type = node.getString("type"),
                platform = node.getString("platform"),
                capabilities = node.getJsonArray("capabilities").map { it as String }
            )
        }

        val mongoConfig = JsonObject()
            .put("connection_string", "mongodb://192.168.68.145:27017/logs")
            .put("db_name", "logs")

        val mongoClient = MongoClient.createShared(vertx, mongoConfig)
        val logService = LogService(vertx, mongoClient)
        FlowTracker.initialize(vertx, logService)
        logger.info { "FlowTracker initialized" }
        val performanceTrackerService = PerformanceTrackerService(vertx, logService)
        val queue = Queue(vertx, concurrency, logService)
        val nodeService = NodeService(vertx, nodes, logService)
        val modelRegistryService = ModelRegistryService(vertx, nodeService, logService)
        val clusterService = ClusterService(nodeService, modelRegistryService, vertx, logService)
        val loadBalancerService = LoadBalancerService(vertx, nodeService, modelRegistryService, performanceTrackerService, logService)
        val adminService = AdminService(vertx, nodeService, loadBalancerService, queue, logService)
        val logViewerHandler = LogViewerHandler(vertx, logService, nodeService, logService)
        val healthHandler = HealthHandler(logService)
        val nodeHandler = NodeHandler(vertx, nodeService, logService)
        val taskDecompositionService = TaskDecompositionService(vertx, modelRegistryService, logService)
        val taskDecompositionHandler = TaskDecompositionHandler(vertx, taskDecompositionService, logService)
        val chatHandler = ChatHandler(vertx, queue, modelRegistryService, nodeService, nodes, performanceTrackerService, loadBalancerService, logService)
        val queueHandler = QueueHandler(queue, logService)
        val clusterHandler = ClusterHandler(vertx, clusterService, logService)
        val adminHandler = AdminHandler(vertx, adminService, loadBalancerService, performanceTrackerService, logService)

        val performanceOptimizationService = PerformanceOptimizationService(
            vertx,
            modelRegistryService,
            nodeService,
            loadBalancerService,
            nodes,
            logService
        )
        val performanceOptimizationHandler = PerformanceOptimizationHandler(
            vertx,
            performanceOptimizationService,
            logService
        )

        val generateHandler = GenerateHandler(
            vertx,
            queue,
            modelRegistryService,
            loadBalancerService,
            nodes,
            performanceTrackerService,
            logService,
            taskDecompositionService,
            performanceOptimizationService
        )
        val agentService = AgentService(
            vertx,
            modelRegistryService,
            loadBalancerService,
            taskDecompositionService,
            performanceTrackerService,
            logService
        )
        val agentHandler = AgentHandler(vertx, agentService, logService)
        val orchestrationPatternService = OrchestrationPatternService(
            vertx,
            modelRegistryService,
            loadBalancerService,
            nodeService,
            nodes,
            agentService,
            taskDecompositionService,
            performanceTrackerService,
            logService
        )
        val orchestrationPatternHandler = OrchestrationPatternHandler(
            vertx,
            orchestrationPatternService,
            logService
        )

        val router = Router.router(vertx)

        router.route().handler(BodyHandler.create())
        router.route().handler(RequestIdHandler.create())
        router.route().handler(LoggingHandler.create(vertx))

        router.get("/api/logs").handler { logViewerHandler.getLogs(it) }
        router.get("/api/servers").handler { logViewerHandler.getServers(it) }
        router.get("/api/levels").handler { logViewerHandler.getLevels(it) }
        router.get("/api/stats").handler { logViewerHandler.getStats(it) }
        router.get("/logviewer/api/logs").handler { logViewerHandler.getLogs(it) }
        router.get("/logviewer/api/servers").handler { logViewerHandler.getServers(it) }
        router.get("/logviewer/api/levels").handler { logViewerHandler.getLevels(it) }
        router.get("/logviewer/api/stats").handler { logViewerHandler.getStats(it) }
        router.get("/api/nodes/names").handler { logViewerHandler.getNodes(it) }

        router.get("/swagger-ui.html").handler(StaticHandler.create("webroot/static"))
        router.get("/openapi.yaml").handler(StaticHandler.create("webroot/static"))
        router.route("/webroot/static/*").handler(StaticHandler.create("webroot/static"))
        router.get("/health").handler { healthHandler.handle(it) }

        router.get("/api/nodes").handler { nodeHandler.listNodes(it) }
        router.get("/api/nodes/status").handler { nodeHandler.getAllNodesStatus(it) }
        router.get("/api/nodes/:name").handler { nodeHandler.getNodeStatus(it) }
        router.get("/api/nodes/:name/models").handler { nodeHandler.getNodeModels(it) }

        router.get("/api/queue/status").handler { queueHandler.getStatus(it) }
        router.post("/api/queue/pause").handler { queueHandler.pauseQueue(it) }
        router.post("/api/queue/resume").handler { queueHandler.resumeQueue(it) }

        router.post("/api/generate").handler { generateHandler.handle(it) }
        router.post("/api/chat").handler { chatHandler.handle(it) }
        router.post("/api/chat/stream").handler { chatHandler.handleStream(it) }

        router.get("/admin/metrics").handler { adminHandler.getMetrics(it) }
        router.get("/admin/metrics/prometheus").handler { adminHandler.getPrometheusMetrics(it) }
        router.get("/admin/requests").handler { adminHandler.getRequestStatistics(it) }
        router.get("/admin/health").handler { adminHandler.getNodeHealth(it) }
        router.get("/admin/system").handler { adminHandler.getSystemInfo(it) }
        router.post("/admin/reset-stats").handler { adminHandler.resetStatistics(it) }
        router.get("/admin/load-balancing").handler { adminHandler.getLoadBalancingMetrics(it) }
        router.get("/admin/performance").handler { adminHandler.getPerformanceMetrics(it) }

        router.get("/api/cluster/models").handler { clusterHandler.getAllModels(it) }
        router.get("/api/cluster/models/:modelId").handler { clusterHandler.checkModelAvailability(it) }
        router.get("/api/cluster/status").handler { clusterHandler.getClusterStatus(it) }
        router.get("/api/cluster/metrics").handler { clusterHandler.getClusterMetrics(it) }
        router.post("/api/cluster/reset-stats").handler { clusterHandler.resetClusterStats(it) }
        router.get("/api/cluster/logs").handler { clusterHandler.getClusterLogs(it) }

        router.post("/api/workflow/decompose/chat").handler { taskDecompositionHandler.decomposeChatRequest(it) }
        router.post("/api/workflow/decompose/generate").handler { taskDecompositionHandler.decomposeGenerateRequest(it) }
        router.get("/api/workflow/:id").handler { taskDecompositionHandler.getWorkflow(it) }
        router.get("/api/workflow/:id/next").handler { taskDecompositionHandler.getNextTasks(it) }
        router.put("/api/workflow/:workflowId/task/:taskId").handler { taskDecompositionHandler.updateTaskStatus(it) }
        router.get("/api/workflow/:id/result").handler { taskDecompositionHandler.getFinalResult(it) }
        router.post("/api/workflow/cleanup").handler { taskDecompositionHandler.cleanupWorkflows(it) }

        router.post("/api/agents/conversations").handler { agentHandler.createConversation(it) }
        router.get("/api/agents/conversations/:id").handler { agentHandler.getConversation(it) }
        router.post("/api/agents/conversations/:id/messages").handler { agentHandler.addMessage(it) }
        router.post("/api/agents/cleanup").handler { agentHandler.cleanupConversations(it) }

        router.post("/api/patterns/ensemble").handler { orchestrationPatternHandler.executeModelEnsemble(it) }
        router.post("/api/patterns/debate").handler { orchestrationPatternHandler.executeDebatePattern(it) }
        router.post("/api/patterns/maestro").handler { orchestrationPatternHandler.executeMAESTROWorkflow(it) }
        router.get("/api/patterns/execution/:id").handler { orchestrationPatternHandler.getExecutionStatus(it) }
        router.post("/api/patterns/cleanup").handler { orchestrationPatternHandler.cleanupExecutions(it) }

        router.post("/api/performance/prewarm").handler { performanceOptimizationHandler.preWarmModel(it) }
        router.get("/api/performance/parameters/:model/:taskType").handler { performanceOptimizationHandler.getOptimizedParameters(it) }
        router.get("/api/performance/cache/stats").handler { performanceOptimizationHandler.getCacheStats(it) }
        router.get("/api/performance/batch/stats").handler { performanceOptimizationHandler.getBatchQueueStats(it) }

        router.get("/api/flow/stats").handler { ctx ->
            val stats = FlowTracker.getStatistics()
            val response = ApiResponse.success(stats)
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(JsonObject.mapFrom(response).encode())
        }

        router.get("/api/flow/:requestId").handler { ctx ->
            val requestId = ctx.pathParam("requestId")
            val flowInfo = FlowTracker.getFlowInfoAsJson(requestId)

            if (flowInfo != null) {
                val response = ApiResponse.success(flowInfo)
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(JsonObject.mapFrom(response).encode())
            } else {
                val response = ApiResponse.error<Nothing>("Flow not found")
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(404)
                    .end(JsonObject.mapFrom(response).encode())
            }
        }

        val staticHandler = StaticHandler.create()
            .setWebRoot("webroot")
            .setCachingEnabled(false)
            .setDirectoryListing(false)
        router.route("/static/*").handler(staticHandler)
        router.get("/swagger-ui.html").handler { ctx ->
            ctx.response().sendFile("webroot/static/swagger-ui.html")
        }

        router.get("/openapi.yaml").handler { ctx ->
            ctx.response().sendFile("webroot/static/openapi.yaml")
        }
        router.route("/logviewer/*").handler(StaticHandler.create("webroot/logviewer"))
        router.get("/logviewer").handler { ctx ->
            ctx.reroute("/logviewer/index.html")
        }
        router.get("/test").handler { ctx ->
            ctx.response().end("Router is working!")
        }

        val server = vertx.createHttpServer()
            .requestHandler(router)
            .listen(serverPort)
            .coAwait()

        logger.info { "Server started on port ${server.actualPort()}" }
        logger.info { "🚀 Server running at http://localhost:${server.actualPort()}/logviewer" }

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutting down server..." }
            queue.close()
        })
    }
}
