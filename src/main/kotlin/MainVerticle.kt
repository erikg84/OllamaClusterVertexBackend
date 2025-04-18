package com.dallaslabs

import com.dallaslabs.config.ConfigLoader
import com.dallaslabs.handlers.*
import com.dallaslabs.models.Node
import com.dallaslabs.services.AdminService
import com.dallaslabs.services.ClusterService
import com.dallaslabs.services.NodeService
import com.dallaslabs.utils.Queue
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
            val node = nodeJson as io.vertx.core.json.JsonObject
            Node(
                name = node.getString("name"),
                host = node.getString("host"),
                port = node.getInteger("port"),
                type = node.getString("type"),
                platform = node.getString("platform"),
                capabilities = node.getJsonArray("capabilities").map { it as String }
            )
        }

        // Initialize services and handlers
        val queue = Queue(vertx, concurrency)
        val nodeService = NodeService(vertx, nodes)
        val clusterService = ClusterService(vertx, nodes, nodeService)
        val adminService = AdminService(vertx, nodeService, queue)

        val healthHandler = HealthHandler()
        val nodeHandler = NodeHandler(vertx, nodeService)
        val generateHandler = GenerateHandler(vertx, queue, nodes)
        val chatHandler = ChatHandler(vertx, queue, nodes)
        val queueHandler = QueueHandler(queue)
        val clusterHandler = ClusterHandler(vertx, clusterService)
        val adminHandler = AdminHandler(vertx, adminService)

        val router = Router.router(vertx)

        router.route().handler(BodyHandler.create())
        router.route().handler(RequestIdHandler.create())
        router.route().handler(LoggingHandler.create(vertx))

        router.get("/swagger-ui.html").handler(StaticHandler.create("static"))
        router.get("/openapi.yaml").handler(StaticHandler.create("static"))
        router.route("/static/*").handler(StaticHandler.create("static"))
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
        router.get("/api/cluster/status").handler { clusterHandler.getStatus(it) }
        router.get("/api/cluster/models").handler { clusterHandler.getAllModels(it) }
        router.get("/api/cluster/models/:modelId").handler { clusterHandler.checkModelAvailability(it) }
        router.get("/admin/metrics").handler { adminHandler.getMetrics(it) }
        router.get("/admin/metrics/prometheus").handler { adminHandler.getPrometheusMetrics(it) }
        router.get("/admin/requests").handler { adminHandler.getRequestStatistics(it) }
        router.get("/admin/health").handler { adminHandler.getNodeHealth(it) }
        router.get("/admin/system").handler { adminHandler.getSystemInfo(it) }
        router.post("/admin/reset-stats").handler { adminHandler.resetStatistics(it) }

        router.get("/test").handler { ctx ->
            ctx.response().end("Router is working!")
        }

        val server = vertx.createHttpServer()
            .requestHandler(router)
            .listen(serverPort)
            .coAwait()

        logger.info { "Server started on port ${server.actualPort()}" }
        logger.info { "🚀 Server running at http://localhost:${server.actualPort()}/swagger-ui.html" }
        logger.info { "📄 OpenAPI spec at http://localhost:${server.actualPort()}/openapi.yaml" }

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutting down server..." }
            queue.close()
        })
    }
}
