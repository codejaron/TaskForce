# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## 🚧 免责声明

本项目处于早期开发阶段，主要用于个人学习和技术探索。代码可能有 Bug，接口可能会变动。欢迎学习交流，但请勿用于生产环境。

## 简介
TaskForce 是一个基于 Plan-Execute 架构的 AI Agent 平台，支持 MCP 工具集成。

采用角色分离 + 上下文隔离的设计：

- **Planner**：分析需求，生成结构化执行计划
- **Worker**：独立执行每个步骤，上下文干净不累积
- **Artifact**：在步骤间传递关键结果

## 核心特性
- **Plan-Execute 分离**：规划和执行解耦，职责清晰
- **上下文隔离**：每个 Worker 只接收当前任务 + 必要的 Artifact，不被无关历史污染
- **实时可观测**：SSE 推送执行状态，知道每一步在干什么
- **错误自动恢复**：失败时触发 Replanner 重新规划
- **MCP 工具集成**：标准化的工具接入方式

📖 详细介绍：[blog.jarontech.top](https://blog.jarontech.top)

---

## 🚀 快速开始（Docker 一键启动）

### 一键启动

```bash
./start.sh
```

启动完成后访问：
- **前端**: http://localhost:3000
- **后端 API**: http://localhost:8080

**首次使用配置**：
1. 访问前端界面
2. 进入"提供商管理"，添加 LLM Provider（OpenAI/Azure/Ollama 等）
3. 进入"智能体管理"，创建：
   - 至少 1 个 **PLANNER** 类型的 Agent（用于生成执行计划）
   - 至少 1 个 **WORKER** 类型的 Agent（用于执行任务）
4. 为 Workers 配置 MCP 工具（可选，增强能力）
5. 创建会话并开始对话

### 停止服务

```bash
./stop.sh
```

## 📱 页面展示

### 仪表板
![仪表板](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.15.03@2x.png)

### Agent 工作坊
![Agent工作坊](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.34.21@2x.png)

### MCP 工具市场
![MCP工具配置](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.34.59@2x.png)

### A2A 工作台
![A2A工作台](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.32.40@2x.png)

## 📘 文档

- 快速开始 / 环境变量 / 常见问题：[`QUICKSTART.md`](./QUICKSTART.md)

## 🏗️ 项目结构

```text
TaskForce/
├── TaskForceFrontEnd/         # React 前端应用
├── TaskForceBackEnd/          # Spring Boot 后端
├── docker-compose.yml         # Docker Compose 配置
├── start.sh                   # 一键启动脚本（自动生成 .env）
├── stop.sh                    # 停止服务脚本
├── mcp-config.json            # MCP 配置（挂载进后端容器）
├── mcp-tools/                 # MCP 工具目录（挂载进后端容器）
└── .env.example               # 环境变量模板
```

## 🧑‍💻 开发模式

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

## 技术栈

### 后端
- Spring Boot
- Java
- MySQL

### 前端
- React
- TypeScript
- Vite
- TailwindCSS
