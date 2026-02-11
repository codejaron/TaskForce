# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## 🚧 免责声明

本项目处于早期开发阶段，主要用于个人学习和技术探索。代码可能有 Bug，欢迎学习交流。

## 简介

TaskForce 是一个基于 **Plan-Execute 架构**的 AI Agent 编排平台，采用角色分离和上下文隔离设计，支持 MCP 工具集成和 Agent-to-Agent 通信。

### 核心设计理念

- **Planner Agent**：分析用户需求，生成结构化的 DAG 执行计划，支持 Self-Correction 重试
- **Worker Agent**：独立执行计划步骤，基于 ReAct 模式调用 MCP 工具，上下文干净不累积
- **层级并行执行**：同层级步骤并行执行，依赖步骤按拓扑顺序执行
- **状态管理**：基于 Redis Hash 的计划状态缓存，支持断点续传
- **实时可观测**：通过 Redis EventBus + SSE 推送执行状态和工具调用事件

📖 详细介绍：[blog.jarontech.top](https://blog.jarontech.top)

---

## 🏗️ 后端架构

### 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                    TaskForce Backend                         │
│                  (Spring Boot 3.3.4 + Java 21)              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │              Orchestration Layer                    │    │
│  │  ┌──────────────┐         ┌──────────────┐        │    │
│  │  │ PlannerNode  │────────▶│ WorkerNode   │        │    │
│  │  │              │         │              │        │    │
│  │  │ - 生成计划    │         │ - 层级并行    │        │    │
│  │  │ - DAG 验证   │         │ - ReAct 循环 │        │    │
│  │  │ - 重试机制    │         │ - 工具调用    │        │    │
│  │  └──────────────┘         └──────────────┘        │    │
│  └────────────────────────────────────────────────────┘    │
│                          │                                  │
│  ┌────────────────────────────────────────────────────┐    │
│  │              State Management                       │    │
│  │  ┌──────────────┐  ┌──────────────┐               │    │
│  │  │ StateManager │  │ EventBus     │               │    │
│  │  │              │  │              │               │    │
│  │  │ - Redis Hash │  │ - Redis Pub  │               │    │
│  │  │ - Plan Cache │  │ - SSE Stream │               │    │
│  │  └──────────────┘  └──────────────┘               │    │
│  └────────────────────────────────────────────────────┘    │
│                          │                                  │
│  ┌────────────────────────────────────────────────────┐    │
│  │              Infrastructure                         │    │
│  │  ┌──────────────┐  ┌──────────────┐               │    │
│  │  │ReactAgent    │  │RemoteMcp     │               │    │
│  │  │Factory       │  │Client        │               │    │
│  │  │              │  │              │               │    │
│  │  │ - 构建 Agent │  │ - MCP 集成   │               │    │
│  │  │ - 工具注入   │  │ - SSE 连接   │               │    │
│  │  └──────────────┘  └──────────────┘               │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

### 执行流程

1. **用户输入** → PlannerNode 生成执行计划（JSON 格式，包含步骤依赖关系）
2. **DAG 验证** → 拓扑排序，按层级分组步骤
3. **并行执行** → WorkerNode 并行执行同层级步骤
4. **工具调用** → ReactAgent 通过 MCP Client 调用工具
5. **状态同步** → StateManager 更新 Redis，EventBus 推送 SSE 事件
6. **失败恢复** → 步骤失败触发 Replanner 重新规划

### 关键特性

- **上下文隔离**：每个 Worker 只接收当前步骤描述 + 依赖步骤的 Artifact，不被历史对话污染
- **Self-Correction**：Planner 生成计划失败时自动重试，附带错误信息进行自我修正
- **ReAct 循环限制**：Worker 最多执行 20 次 ReAct 迭代，防止无限循环
- **分布式锁**：基于 Redisson 的分布式锁，保证并发安全
- **数据库记忆**：DbChatMemory 持久化对话历史，支持会话恢复

---

## 🔧 MCP Server 微服务

独立的 MCP 工具服务，统一管理和路由 MCP 工具调用。

### 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                   MCP Server 微服务                          │
│                (Spring Boot 3.3.4 + Java 21)                │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐   │
│  │                对外统一接口                           │   │
│  │  GET  /mcp/sse     → SSE 长连接                      │   │
│  │  POST /mcp/message → JSON-RPC 消息处理               │   │
│  │  GET  /api/tools   → 工具列表                        │   │
│  │  POST /api/tools/call → 工具调用                     │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  ToolRouter                          │   │
│  │           根据 tool_name 路由到对应 Provider          │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                 │                      │
│         ┌───────────────┐     ┌─────────────┐              │
│         │StdioProvider  │     │RemoteSse    │              │
│         │               │     │Provider     │              │
│         │ - npx 子进程  │     │ - 远程转发  │              │
│         │ - weather     │     │ - n8n 集成  │              │
│         │ - filesystem  │     │             │              │
│         └───────────────┘     └─────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

### 支持的工具类型

1. **STDIO 工具**：通过 `npx` 启动的 MCP Server（如 `@modelcontextprotocol/server-filesystem`）
2. **Remote SSE 工具**：远程 SSE 服务（如 n8n 集成的工具）

### 配置方式

- **数据库配置**：通过 `tool_provider_config` 表管理
- **JSON 文件配置**：通过 `mcp-server-config.json` 文件管理
- **混合模式**：同时支持数据库和文件配置

### 与主服务集成

主服务（TaskForceBackEnd）通过 `RemoteMcpClient` 连接到 MCP Server：
- 建立 SSE 长连接接收工具列表
- 通过 JSON-RPC 协议调用工具
- 支持服务发现（Nacos）和负载均衡

---

## 🚀 快速开始

### 一键启动（Docker Compose）

```bash
./start.sh
```

启动完成后访问：
- **前端**: http://localhost:3000
- **后端 API**: http://localhost:8080
- **MCP Server**: http://localhost:8082

### 服务依赖

Docker Compose 会自动启动以下服务：
- **MySQL 8.0** - 数据持久化
- **Redis 7** - 状态缓存和事件总线
- **RocketMQ 5.1.0** - 消息队列（Namesrv + Broker + Dashboard）
- **TaskForce Backend** - 主服务
- **MCP Server** - MCP 工具服务
- **Frontend** - Web 界面

### 首次使用配置

1. 访问前端界面 http://localhost:3000
2. 进入"提供商管理"，添加 LLM Provider（OpenAI/Azure/Ollama 等）
3. 进入"智能体管理"，创建：
   - 至少 1 个 **PLANNER** 类型的 Agent
   - 至少 1 个 **WORKER** 类型的 Agent
4. 为 Workers 配置 MCP 工具（可选）
5. 创建会话并开始对话

### 停止服务

```bash
./stop.sh
```

---

## 📦 技术栈

### 后端核心依赖

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.3.4 | 应用框架 |
| Java | 21 | 编程语言 |
| Spring AI | 1.1.2 | LLM 集成（OpenAI/Azure/Ollama） |
| Spring AI Alibaba | 1.1.2.0 | Agent Framework + Sandbox |
| AgentScope A2A | 1.0.2 | Agent-to-Agent 通信 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| MySQL | 8.0 | 关系数据库 |
| Redis | 7 | 缓存 + 状态管理 |
| Redisson | 3.27.0 | 分布式锁 |
| RocketMQ | 5.1.0 | 消息队列 |
| Nacos | 2023.0.1.0 | 服务发现 |
| Druid | 1.2.20 | 数据库连接池 |

### MCP Server 核心依赖

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.3.4 | 应用框架 |
| Spring AI | 1.1.2 | Spring AI 框架（BOM 管理） |
| MCP SDK | 0.17.0 | MCP 协议实现（原生客户端） |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| Nacos | 2023.0.1.0 | 服务注册 |

---

## 🗂️ 项目结构

```text
TaskForce/
├── TaskForceBackEnd/                    # 后端主服务
│   ├── src/main/java/com/agent/
│   │   ├── api/                         # REST API 控制器
│   │   ├── domain/
│   │   │   ├── orchestration/           # 编排核心
│   │   │   │   ├── graph/
│   │   │   │   │   ├── node/            # PlannerNode, WorkerNode
│   │   │   │   │   ├── parallel/        # ParallelExecutor
│   │   │   │   │   ├── dispatcher/      # 调度器
│   │   │   │   │   └── topology/        # DAG 拓扑排序
│   │   │   │   ├── state/               # StateManager
│   │   │   │   ├── model/               # ExecutionPlan, PlanStep
│   │   │   │   └── validator/           # DAGValidator
│   │   │   └── context/                 # 上下文管理
│   │   ├── infrastructure/
│   │   │   ├── llm/                     # LlmAdapter, ChatModelFactory
│   │   │   ├── agent/                   # ReactAgentFactory
│   │   │   ├── mcp/                     # RemoteMcpClient
│   │   │   ├── event/                   # EventBus (Redis)
│   │   │   ├── memory/                  # DbChatMemory
│   │   │   ├── prompt/                  # PromptManager
│   │   │   └── persistence/             # Mapper, Entity
│   │   └── service/                     # 业务服务层
│   └── src/main/resources/
│       ├── application.yml              # 配置文件
│       └── db/migration/                # 数据库 Schema
│
├── mcp-server/                          # MCP Server 微服务
│   ├── src/main/java/com/agent/mcpserver/
│   │   ├── controller/                  # MCP 协议控制器
│   │   │   ├── McpSseController.java    # SSE 长连接
│   │   │   ├── McpStreamableHttpController.java  # JSON-RPC
│   │   │   ├── ToolController.java      # REST API
│   │   │   └── ProviderController.java  # 提供者管理
│   │   ├── service/
│   │   │   └── provider/                # StdioProvider, RemoteSseProvider
│   │   ├── protocol/                    # JSON-RPC 协议
│   │   ├── entity/                      # ToolProviderConfig
│   │   └── tool/                        # 工具定义
│   └── README.md                        # MCP Server 文档
│
├── docker-compose.yml                   # Docker Compose 配置
├── start.sh                             # 一键启动脚本
├── stop.sh                              # 停止服务脚本
└── .env.example                         # 环境变量模板
```

---

## 🧑‍💻 开发指南

### 本地开发（后端）

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

**前置条件**：
- 启动 MySQL（端口 3306）
- 启动 Redis（端口 6379）
- 启动 RocketMQ Namesrv（端口 9876）
- 启动 MCP Server（端口 8082）

### 本地开发（MCP Server）

```bash
cd mcp-server
mvn spring-boot:run
```

**前置条件**：
- 启动 MySQL（端口 3306）
- 启动 RocketMQ Namesrv（端口 9876）

### 配置文件

- **后端配置**：`TaskForceBackEnd/src/main/resources/application.yml`
- **MCP Server 配置**：`mcp-server/src/main/resources/application.yml`
- **环境变量**：`.env`（从 `.env.example` 复制）

### 数据库初始化

数据库 Schema 会在首次启动时自动创建（通过 `schema.sql`）。

---

## 📘 文档

- **快速开始 / 环境变量 / 常见问题**：[`QUICKSTART.md`](./QUICKSTART.md)
- **MCP Server 详细文档**：[`mcp-server/README.md`](./mcp-server/README.md)

---

## 🔗 相关链接

- 博客：[blog.jarontech.top](https://blog.jarontech.top)
- Spring AI：[docs.spring.io/spring-ai](https://docs.spring.io/spring-ai)
- Spring AI Alibaba：[github.com/alibaba/spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba)
- MCP Protocol：[modelcontextprotocol.io](https://modelcontextprotocol.io)

---

## 📄 License

MIT
