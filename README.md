# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## 项目概览

TaskForce 是一个面向本地运行的多智能体协作平台，提供两种核心工作模式：

- `Team`：由 Team Lead 协调多个 Worker 执行任务
- `Single Chat`：单智能体直接对话

平台内置 MCP 工具接入能力、实时事件流和会话级状态管理，适合用于智能体协作流程验证、工具编排实验和本地 AI 应用开发。

## 核心能力

- 多智能体协作：Team Lead 负责拆解任务、调度执行、汇总结果
- Worker 执行链路：Worker 可独立调用 MCP 工具并持续上报进度
- 实时可观测：基于 SSE 推送 Team/Worker 事件流
- 会话状态管理：支持会话过程数据持久化与继续执行
- MCP 工具生态：支持 STDIO 与远程 SSE 两类 MCP Provider
- 双模式运行：`Team` 与 `Single Chat` 可按场景切换

📖 项目介绍：[blog.jarontech.top](https://blog.jarontech.top)

---

## 系统组成

- `TaskForceFrontEnd`：React + Vite 前端，提供配置与会话界面
- `TaskForceBackEnd`：Spring Boot 主服务，负责任务编排与业务 API
- `mcp-server`：独立 MCP 工具服务，负责工具注册、路由与调用

## 运行流程

1. 配置 LLM Provider
2. 创建 Team Lead 与 Worker 智能体
3. 发起 `TEAM` 或 `CHAT` 会话
4. 通过前端实时观察执行事件
5. 查看历史消息与工具调用结果

---

## 快速开始（本地）

### 1. 准备依赖服务

请先启动以下服务（默认端口）：

- MySQL 8.0（`3306`）
- Redis 7（`6379`）
- Nacos（`8848`，如启用服务发现）

### 2. 启动 MCP Server

```bash
cd mcp-server
mvn spring-boot:run
```

### 3. 启动后端

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

### 4. 启动前端

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

常用本地地址：

- 前端：`http://localhost:5173`
- 后端 API：`http://localhost:8080`
- MCP Server：`http://localhost:8082`

### 5. 首次使用配置

1. 打开前端 `http://localhost:5173`
2. 在“提供商管理”中添加模型提供商（OpenAI/Azure/Ollama 等）
3. 在“智能体管理”中创建：
   - 至少 1 个 Team Lead 智能体
   - 至少 1 个 Worker 智能体
4. 为 Worker 绑定 MCP 工具（可选）
5. 创建 `TEAM` 或 `CHAT` 会话并开始使用

---

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.3.4 | 应用框架 |
| Java | 21 | 编程语言 |
| Spring AI | 1.1.2 | LLM 集成 |
| Spring AI Alibaba | 1.1.2.0 | Agent Framework + Sandbox |
| MyBatis-Plus | 3.5.5 | ORM |
| MySQL | 8.0 | 关系数据库 |
| Redis | 7 | 缓存与状态管理 |
| Redisson | 3.27.0 | 分布式锁 |
| Nacos | 2023.0.1.0 | 服务发现 |

### 前端

- React
- TypeScript
- Vite
- TailwindCSS

### MCP Server

| 技术 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.3.4 | 应用框架 |
| Spring AI | 1.1.2 | AI 能力集成 |
| MCP SDK | 0.17.0 | MCP 协议实现 |
| MyBatis-Plus | 3.5.5 | ORM |

---

## 项目结构

```text
TaskForce/
├── TaskForceBackEnd/          # 后端主服务
├── TaskForceFrontEnd/         # 前端应用
├── mcp-server/                # MCP Server 微服务
├── .env.example               # 本地环境变量示例
├── QUICKSTART.md              # 中文快速开始
└── QUICKSTART_EN.md           # English quick start
```

---

## 开发说明

### 后端开发

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

### 前端开发

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

### MCP Server 开发

```bash
cd mcp-server
mvn spring-boot:run
```

配置文件位置：

- 后端：`TaskForceBackEnd/src/main/resources/application.yml`
- MCP Server：`mcp-server/src/main/resources/application.yml`
- 环境变量示例：`.env.example`

---

## 文档

- 快速开始与常见问题：[`QUICKSTART.md`](./QUICKSTART.md)
- MCP Server 说明：[`mcp-server/README.md`](./mcp-server/README.md)

---

## License

MIT
