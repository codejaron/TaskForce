# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## 🚧 Disclaimer

This project is in early development stage, primarily for personal learning and technical exploration. The code may contain bugs, and interfaces may change. Welcome to learn and exchange ideas, but please do not use in production environments.

## Introduction
TaskForce is an AI Agent platform based on Plan-Execute architecture, supporting MCP tool integration.

It adopts a role separation + context isolation design:

- **Planner**: Analyzes requirements and generates structured execution plans
- **Worker**: Independently executes each step with clean, non-accumulating context
- **Artifact**: Passes key results between steps

## Core Features
- **Plan-Execute Separation**: Decoupled planning and execution with clear responsibilities
- **Context Isolation**: Each Worker only receives current task + necessary Artifacts, not polluted by irrelevant history
- **Real-time Observability**: SSE pushes execution status, know what each step is doing
- **Automatic Error Recovery**: Triggers Replanner to replan when failures occur
- **MCP Tool Integration**: Standardized tool integration approach

📖 Detailed Introduction: [blog.jarontech.top](https://blog.jarontech.top)

---

## 🚀 Quick Start (Docker one-command)

### Start

```bash
./start.sh
```

After startup:

- **Frontend**: http://localhost:3000
- **Backend API**: http://localhost:8080

**Initial Configuration**:
1. Visit frontend interface
2. Go to "Provider Management", add LLM Provider (OpenAI/Azure/Ollama, etc.)
3. Go to "Agent Management", create:
   - At least 1 **PLANNER** type Agent (for generating execution plans)
   - At least 1 **WORKER** type Agent (for executing tasks)
4. Configure MCP tools for Workers (optional, enhances capabilities)
5. Create session and start conversation

### Stop

```bash
./stop.sh
```

## 📘 Docs

- Quick start / env vars / FAQ: [QUICKSTART_EN.md](./QUICKSTART_EN.md) (English)

## 🏗️ Project Structure

```text
TaskForce/
├── TaskForceFrontEnd/         # React frontend
├── TaskForceBackEnd/          # Spring Boot backend
├── docker-compose.yml         # Docker Compose config
├── start.sh                   # One-command startup (auto-generates .env)
├── stop.sh                    # Stop services
├── mcp-config.json            # MCP config (mounted into backend container)
├── mcp-tools/                 # MCP tools directory (mounted into backend container)
└── .env.example               # Environment variables template
```

## 🧑‍💻 Development

### Backend

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

### Frontend

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

## Tech Stack

### Backend

- Spring Boot
- Java
- MySQL

### Frontend

- React
- TypeScript
- Vite
- TailwindCSS
