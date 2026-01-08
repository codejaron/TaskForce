# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

> 🚧 **免责声明 / Disclaimer**
> 
> 本项目目前处于 **早期开发/起步阶段**，主要用于**个人学习和技术验证**。
> 代码中可能包含较多 Bug，功能和接口可能会频繁变动。欢迎用于学习交流，但**请勿在生产环境中使用**。

一个基于 **Spring Boot** 和 **React + Vite** 的智能对话/Agent 平台，支持 MCP 工具集成。

## GroupChat 工作流

TaskForce 使用 **Plan-Execute 异步架构**实现智能任务编排。

### 执行流程

```
用户输入 → 创建会话
    ↓
PlannerAgent 分析需求
    ↓
生成 ExecutionPlan → 存储到 execution_plan 表
    ↓                       ↓
前端显示计划 ← PlanGeneratedEvent
    ↓
WorkflowEngine 异步执行
    ↓
StepExecutor 执行每个步骤
    ↓
调用 Worker (with MCP tools)
    ↓
Worker 输出 → 提取 Artifact → session_artifact 表
    ↓                               ↓
EventBus 推送事件        供后续步骤使用
    ↓
前端实时更新（SSE 流）
    ↓
所有步骤完成 → SessionCompleteEvent
```

### 关键特性

1. **Fire-and-forget 执行**：HTTP 请求立即返回，不阻塞等待执行完成
2. **事件驱动通信**：前端和后端通过独立的 SSE 连接实时同步
3. **错误自动恢复**：遇到错误时触发 ReplannerAgent 自动修复
4. **结构化上下文**：通过 Artifact 系统在步骤间传递结构化数据
5. **可暂停/恢复**：需要用户输入时自动暂停，收到输入后继续执行

---

## 上下文管理：Artifact 系统

TaskForce 使用 **Artifact 系统** 进行会话级别的上下文管理。

### Artifact 系统工作原理

#### 1. 存储结构
- **数据库表**：`session_artifact`
- **字段**：
  - `session_id`：会话 ID
  - `artifact_key`：数据标识（如 "PLAN", "search_results", "generated_code"）
  - `artifact_value`：LONGTEXT 内容
  - 唯一约束：`(session_id, artifact_key)`

#### 2. XML 标签格式
Workers 使用 XML 标签输出结构化数据：
```xml
<artifact key="search_results">
1. 搜索结果 A
2. 搜索结果 B
</artifact>

<artifact key="generated_code">
public class Example {
    // 代码内容
}
</artifact>
```

#### 3. 上下文传递流程
```
PlannerAgent 生成 ExecutionPlan
    ↓
存储到 execution_plan 表（JSON格式）
    ↓
WorkflowEngine 开始执行
    ↓
StepExecutor 构建 TaskContext:
  - userGoal (来自 ExecutionPlan.goal)
  - recentHistory (最近3条消息)
  - sharedData (已有的 artifacts)
  - currentStep (当前步骤)
    ↓
Worker A 执行 Step 1
    ↓
输出带 <artifact> 标签的结果
    ↓
StepExecutor 提取并存入 session_artifact 表
    ↓
Worker B 执行 Step 2
    ↓
通过 TaskContext.sharedData 读取 Worker A 的 artifacts
    ↓
继续执行...
```

### 上下文组成

#### PlannerAgent 上下文
- 用户输入的目标描述
- 可用的 Workers 列表（包含各自的工具能力）

#### Worker 上下文
- **用户目标**：会话的总体目标
- **当前步骤描述**：PlannerAgent 分配的具体任务
- **共享数据**：所有已生成的 Artifacts
- **最近对话历史**：最近 3 条消息（了解上下文）

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
