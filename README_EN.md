# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## Overview

TaskForce is a local-first multi-agent collaboration platform with two core runtime modes:

- `Team`: a Team Lead coordinates multiple Workers
- `Single Chat`: direct interaction with one agent

It includes MCP tool integration, real-time event streaming, and session-level state management for building and validating agent workflows.

## Key Capabilities

- Multi-agent collaboration: Team Lead plans, delegates, and consolidates outcomes
- Worker execution pipeline: workers run tasks and call MCP tools independently
- Real-time observability: Team/Worker events are streamed via SSE
- Session state handling: execution data is persisted for continuity
- MCP provider support: both STDIO and remote SSE providers are supported
- Dual runtime modes: switch between `Team` and `Single Chat` by scenario

📖 Project blog: [blog.jarontech.top](https://blog.jarontech.top)

---

## System Components

- `TaskForceFrontEnd`: React + Vite UI for configuration and conversations
- `TaskForceBackEnd`: Spring Boot orchestration and business API service
- `mcp-server`: standalone MCP tool service for registration, routing, and execution

## Runtime Flow

1. Configure an LLM provider
2. Create Team Lead and Worker agents
3. Start a `TEAM` or `CHAT` session
4. Observe execution through live event streams
5. Review conversation and tool-call history

---

## Quick Start (Local)

### 1. Start dependencies

Run these services first (default ports):

- MySQL 8.0 (`3306`)
- Redis 7 (`6379`)
- Nacos (`8848`, if service discovery is enabled)

### 2. Start MCP Server

```bash
cd mcp-server
mvn spring-boot:run
```

### 3. Start backend

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

### 4. Start frontend

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

Local URLs:

- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`
- MCP Server: `http://localhost:8082`

### 5. Initial setup

1. Open `http://localhost:5173`
2. Add an LLM provider in Provider Management (OpenAI/Azure/Ollama, etc.)
3. Create agents in Agent Management:
   - at least 1 Team Lead agent
   - at least 1 Worker agent
4. Bind MCP tools to workers (optional)
5. Create a `TEAM` or `CHAT` session

---

## Tech Stack

### Backend

| Tech | Version | Purpose |
|---|---|---|
| Spring Boot | 3.3.4 | Application framework |
| Java | 21 | Language runtime |
| Spring AI | 1.1.2 | LLM integration |
| Spring AI Alibaba | 1.1.2.0 | Agent framework + sandbox |
| MyBatis-Plus | 3.5.5 | ORM |
| MySQL | 8.0 | Relational storage |
| Redis | 7 | Cache and state management |
| Redisson | 3.27.0 | Distributed lock |
| Nacos | 2023.0.1.0 | Service discovery |

### Frontend

- React
- TypeScript
- Vite
- TailwindCSS

### MCP Server

| Tech | Version | Purpose |
|---|---|---|
| Spring Boot | 3.3.4 | Application framework |
| Spring AI | 1.1.2 | AI integration |
| MCP SDK | 0.17.0 | MCP protocol implementation |
| MyBatis-Plus | 3.5.5 | ORM |

---

## Project Structure

```text
TaskForce/
├── TaskForceBackEnd/          # Backend service
├── TaskForceFrontEnd/         # Frontend app
├── mcp-server/                # MCP service
├── .env.example               # Local env var example
├── QUICKSTART.md              # Chinese quick start
└── QUICKSTART_EN.md           # English quick start
```

---

## Development

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

### MCP Server

```bash
cd mcp-server
mvn spring-boot:run
```

Configuration files:

- Backend: `TaskForceBackEnd/src/main/resources/application.yml`
- MCP Server: `mcp-server/src/main/resources/application.yml`
- Env example: `.env.example`

---

## Documentation

- Quick start and FAQ: [`QUICKSTART_EN.md`](./QUICKSTART_EN.md)
- MCP Server docs: [`mcp-server/README.md`](./mcp-server/README.md)

---

## License

MIT
