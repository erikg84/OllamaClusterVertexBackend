# LLM Orchestration Cluster

> A Comprehensive Enterprise Solution for Large Language Model Management

## 📋 Project Overview

The LLM Orchestration Cluster is an advanced distributed system designed to orchestrate Large Language Models (LLMs) across heterogeneous hardware, enabling organizations to leverage their existing infrastructure for AI workloads. The solution provides sophisticated orchestration patterns, intelligent load balancing, and performance optimizations that collectively enhance both the quality and efficiency of LLM operations.

## 🏗️ System Architecture

The system employs a two-tier architecture:

### Orchestration Layer (Kotlin/Vert.x)
Built on a reactive microservices architecture using Vert.x, the core orchestration layer employs a modular design with clear separation of concerns:

- **Central Orchestration Hub**: Coordinates all system components and routes requests to appropriate services
- **Model Registry**: Maintains information about available models and their locations
- **Node Management**: Tracks available hardware nodes and their capabilities
- **Request Processing Pipeline**: Manages request queuing and concurrency control
- **Monitoring & Administration**: Provides comprehensive observability through metrics and logging

### Node Layer (Node.js/Express)
Each node in the cluster runs a local service that:

- Interfaces directly with Ollama API instances running on the machine
- Manages local request queuing and processing
- Collects and reports detailed metrics
- Handles local caching and optimizations
- Streams responses back to the orchestration layer

The architecture enables horizontal scaling across heterogeneous hardware, from high-powered GPU servers to consumer-grade devices.

## 🔋 Advanced Capabilities

### Orchestration Patterns

- **Model Ensembling**: Combines outputs from multiple models to produce more robust and accurate responses
- **Debate Pattern**: Implements a structured dialectical approach where multiple models engage in rounds of critique and improvement
- **MAESTRO Workflow**: Leverages a multi-agent system where specialized agents collaborate on complex tasks
- **Task Decomposition**: Breaks complex requests into simpler subtasks with defined dependencies

### Multi-Agent Architecture

The system implements a sophisticated agent-based approach with:

- **Coordinator Agent**: Analyzes queries, determines required specialized agents, and synthesizes final responses
- **Specialized Agents**:
    - **Research Agent**: Specializes in information gathering and fact-checking
    - **Reasoning Agent**: Handles logical analysis and problem-solving
    - **Creative Agent**: Generates creative content like stories and essays
    - **Code Agent**: Focuses on programming and technical tasks
    - **Critic Agent**: Reviews outputs for quality, accuracy, and coherence

Each agent selects appropriate models and nodes based on its specialized task requirements.

### Performance Optimization

- **Response Caching**: Implements an LRU cache with TTL expiration for common queries
- **Request Batching**: Groups similar requests to minimize model loading overhead
- **Parameter Optimization**: Automatically selects optimal model parameters based on query analysis
- **Model Pre-warming**: Proactively loads models based on observed query patterns

### Intelligent Load Balancing

The system routes requests based on multiple factors:

- **Current Load**: Distributes requests to maintain balanced system utilization
- **Historical Performance**: Considers past response times and error rates
- **Hardware-Model Affinity**: Matches models to appropriate hardware
- **Failure Detection**: Implements circuit breakers and fallback strategies for node failures

## 📈 Scalability Aspects

### Horizontal Scalability

- **Node Discovery**: New LLM nodes can join the cluster dynamically
- **Capability Registration**: Nodes advertise their models and hardware specifications
- **P2P Architecture**: No central bottleneck; nodes connect directly to form the cluster
- **Dynamic Routing**: Request routing adapts automatically as nodes join or leave

### Performance Scalability

- **Concurrency Control**: Configurable queue depths and parallelism settings
- **Adaptive Batching**: Batch sizes and scheduling adapt to system load
- **Resource-Aware Routing**: Tasks matched to appropriate hardware capabilities
- **Model Partitioning**: Large models can be split across multiple devices

### Operational Scalability

- **Comprehensive Monitoring**: Detailed metrics for performance analysis
- **Prometheus Integration**: Export metrics to industry-standard monitoring tools
- **Administrative APIs**: Programmatic control of cluster configuration
- **MongoDB Integration**: Centralized logging for distributed deployments

## 🛠️ Technical Implementation

### Core Orchestration Layer

- **Primary Language**: Kotlin
- **Framework**: Vert.x for reactive, non-blocking operations
- **Database**: MongoDB for logs and operational metrics
- **Communication**: GRPC for node-to-node communication
- **Data Format**: JSON for structured data interchange

### Node Layer

- **Primary Language**: JavaScript (Node.js)
- **Framework**: Express for HTTP API endpoints
- **Communication**: HTTP/REST for LLM backend interaction
- **Logging**: Winston with MongoDB transport for centralized logging
- **Queue Management**: p-queue for local request concurrency control

The system supports multiple LLM backends and can integrate with proprietary and open-source models via the Ollama API.

## 🔄 Request Flow

Here's how requests flow through the system:

1. **Request Ingestion**: Incoming requests are received via REST API endpoints at the orchestration layer
2. **Request Parsing**: The appropriate handler parses and validates the request
3. **Queue Placement**: Requests are placed in a priority queue for processing
4. **Load Balancing**: The system determines the optimal node for processing
5. **Task Decomposition**: Complex requests may be broken down into sub-tasks
6. **Node Routing**: Requests are forwarded to the appropriate nodes
7. **Local Processing**: Node-level Express servers queue and process requests via Ollama
8. **Result Aggregation**: For multi-node or decomposed requests, results are aggregated
9. **Response Delivery**: Final results are delivered back to the client

For high complexity tasks, the system may employ orchestration patterns like model ensembling or the MAESTRO workflow to produce higher quality outputs.

## 📡 API Documentation

The system exposes a comprehensive REST API for interacting with the cluster:

### Core API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/generate` | POST | Generate text using specified model |
| `/api/chat` | POST | Process chat messages using specified model |
| `/api/chat/stream` | POST | Stream chat responses |

### Orchestration Pattern API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/patterns/ensemble` | POST | Execute model ensemble pattern |
| `/api/patterns/debate` | POST | Execute debate pattern |
| `/api/patterns/maestro` | POST | Execute MAESTRO workflow |
| `/api/patterns/execution/:id` | GET | Get status of pattern execution |

### Agent API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/agents/conversations` | POST | Create a new agent conversation |
| `/api/agents/conversations/:id` | GET | Get conversation details |
| `/api/agents/conversations/:id/messages` | POST | Add message to conversation |

### Cluster Management API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/cluster/models` | GET | List all available models |
| `/api/cluster/status` | GET | Get cluster status |
| `/api/cluster/metrics` | GET | Get cluster performance metrics |
| `/api/nodes` | GET | List all nodes in cluster |
| `/api/nodes/:name/models` | GET | List models available on specific node |

### Admin & Monitoring API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/metrics` | GET | Get system metrics |
| `/admin/metrics/prometheus` | GET | Get metrics in Prometheus format |
| `/admin/health` | GET | Get health status of nodes |
| `/admin/system` | GET | Get system information |
| `/admin/performance` | GET | Get performance metrics |

### Performance Optimization API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/performance/prewarm` | POST | Pre-warm a model |
| `/api/performance/parameters/:model/:taskType` | GET | Get optimized parameters |
| `/api/performance/cache/stats` | GET | Get cache statistics |
| `/api/performance/batch/stats` | GET | Get batch queue statistics |
| `/api/queue/status` | GET | Get queue status |

### Task Decomposition API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/workflow/decompose/chat` | POST | Decompose a chat request into subtasks |
| `/api/workflow/decompose/generate` | POST | Decompose a generate request into subtasks |
| `/api/workflow/:id` | GET | Get workflow information |
| `/api/workflow/:id/next` | GET | Get next executable tasks |
| `/api/workflow/:workflowId/task/:taskId` | PUT | Update task status |
| `/api/workflow/:id/result` | GET | Get final workflow result |
| `/api/workflow/cleanup` | POST | Clean up old workflows |

For detailed API specifications, refer to the Swagger UI available at `/swagger-ui.html` when the server is running.

## ⚙️ Configuration

The system is configured via a YAML file (default: `config.yaml`):

```yaml
server:
  port: 3001             # HTTP server port
  readTimeout: 30        # Read timeout in seconds
  writeTimeout: 30       # Write timeout in seconds
  queueSize: 100         # Maximum queue size
  concurrency: 20        # Number of concurrent requests

mongodb:
  uri: "mongodb://localhost:27017/logs"  # MongoDB connection string
  database: "logs"                       # MongoDB database name

nodes:
  - name: "NODE_1"               # Node name
    host: "192.168.1.101"        # Node hostname or IP
    port: 3000                   # Node port
    type: "gpu"                  # Node type (gpu/cpu)
    platform: "linux"            # Operating system
    capabilities: ["cuda", "avx512"]  # Hardware capabilities
  
  - name: "NODE_2"
    host: "192.168.1.102"
    port: 3000
    type: "cpu"
    platform: "windows"
    capabilities: ["avx512", "parallel"]
```

The node layer is configured through environment variables or command-line parameters for the Express server:

```bash
# Node configuration
PORT=3000                          # Port for the Express server
LLAMA_BASE_URL=http://localhost:11434/api  # URL for the Ollama API
MONGODB_URI=mongodb://localhost:27017/logs # MongoDB connection for logs
NODE_ENV=production                # Environment (development/production)
QUEUE_CONCURRENCY=5                # Local queue concurrency
```

## 🚀 Getting Started

### Prerequisites

- JDK 11+
- Kotlin 1.5+
- Node.js 14+
- MongoDB 4.4+
- Gradle 7.0+
- Ollama (installed on each node)

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/your-org/llm-orchestration-cluster.git
   cd llm-orchestration-cluster
   ```

2. Configure your environment:
    - Copy `config.yaml.example` to `config.yaml`
    - Edit the configuration to match your environment

3. Build the orchestration layer:
   ```bash
   ./gradlew build
   ```

4. Set up the node layer on each machine:
   ```bash
   cd node-layer
   npm install
   ```

5. Start Ollama on each node:
   ```bash
   ollama serve
   ```

6. Start the node layer on each machine:
   ```bash
   npm start
   ```

7. Start the orchestration layer:
   ```bash
   ./gradlew run
   ```

8. Verify installation:
    - Check that the orchestration server is running at http://localhost:3001/health
    - Check that each node server is running at http://{node-ip}:3000/health
    - Access the log viewer at http://localhost:3001/logviewer
    - Access the Swagger UI at http://localhost:3001/swagger-ui.html

### Docker Deployment

To run using Docker:

```bash
# Build and start the orchestration layer
docker build -t llm-orchestration -f Dockerfile.orchestration .
docker run -p 3001:3001 llm-orchestration

# On each node, build and start the node layer
docker build -t llm-node -f Dockerfile.node .
docker run -p 3000:3000 llm-node
```

For a full Docker Compose setup with MongoDB:

```bash
docker-compose up -d
```

## 📊 Monitoring & Administration

The system provides several monitoring and administration interfaces:

### Web Interfaces

- **Log Viewer**: Available at `/logviewer` - shows system logs with filtering
- **Swagger UI**: Available at `/swagger-ui.html` - interactive API documentation
- **Prometheus Metrics**: Available at `/admin/metrics/prometheus` - metrics in Prometheus format

### Key Metrics

- **Request Throughput**: Number of requests processed per minute
- **Response Time**: Average response time by model and node
- **Queue Size**: Current number of requests in queue
- **Error Rate**: Percentage of failed requests
- **Node Status**: Health status of each node
- **Model Usage**: Usage statistics by model
- **Resource Utilization**: CPU, memory, and GPU utilization per node

## 💼 Use Cases

The LLM Orchestration Cluster is well-suited for:

- **Enterprise Knowledge Systems**: Leveraging organizational knowledge bases with advanced LLM capabilities
- **Multi-Model Research**: Comparing and combining outputs from different models
- **Hardware Optimization**: Maximizing utilization of existing hardware investments
- **Edge AI Deployment**: Distributing LLM workloads across edge devices
- **High-Reliability Systems**: Ensuring continued operation even when some nodes fail

## 🔍 Troubleshooting

Common issues and their solutions:

- **Connection Refused**: Ensure all configured nodes are running and accessible
- **MongoDB Connection Errors**: Verify MongoDB connection string and ensure MongoDB is running
- **Slow Response Times**: Check node load metrics and consider scaling horizontally
- **Out of Memory Errors**: Adjust concurrency settings or add more nodes
- **Model Not Found**: Ensure the requested model is available on at least one node

For detailed logs, check:
- Orchestration layer logs in the console or MongoDB collection
- Node layer logs in `server.log` or MongoDB collection
- Log viewer at `/logviewer`

## 🤝 Contributing

Guidelines for contributing to this project:

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -am 'Add new feature'`
4. Push to the branch: `git push origin feature/my-feature`
5. Submit a pull request

Please ensure your code follows our coding standards and includes appropriate tests.

## 📄 License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## 📞 Support

For support, please:
- Check the [FAQ](docs/FAQ.md)
- Open an issue on GitHub
- Contact support@example.com

---

© 2025 LLM Orchestration Project
