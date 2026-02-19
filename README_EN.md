<div align="center">
  <h1>TaskForce</h1>
  <p><strong>A Multi-Agent Orchestration Platform for Cloud and Private Deployment (Java / Spring)</strong></p>
  <p>
    <img src="https://img.shields.io/badge/java-21-6b6b6b?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21" />
    <img src="https://img.shields.io/badge/spring_boot-3.3.4-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 3.3.4" />
    <img src="https://img.shields.io/badge/spring_ai-1.1.2-0ea5e9?style=flat-square" alt="Spring AI 1.1.2" />
    <img src="https://img.shields.io/badge/spring_ai_alibaba-1.1.2.0-f97316?style=flat-square" alt="Spring AI Alibaba 1.1.2.0" />
    <img src="https://img.shields.io/badge/react-19.2.0-2563eb?style=flat-square&logo=react&logoColor=white" alt="React 19.2.0" />
    <img src="https://img.shields.io/badge/redis-7-dc2626?style=flat-square&logo=redis&logoColor=white" alt="Redis 7" />
    <img src="https://img.shields.io/badge/license-apache_2.0-16a34a?style=flat-square" alt="Apache 2.0" />
  </p>
  <p>
    <a href="./README.md">中文</a> |
    <a href="./README_EN.md">English</a> |
    <a href="./QUICKSTART_EN.md">Quick Start</a> |
    <a href="./mcp-server/README.md">MCP Server Docs</a>
  </p>
</div>

## Overview

TaskForce is a multi-agent collaboration system designed for cloud and private deployment.  
Its core model is `Team Lead + Worker`: the Lead decomposes and schedules work, Workers execute and report back, and the system keeps progressing through event-driven loops until the session is completed. It includes distributed session ownership, event stream recovery, and object storage sync for real engineering workloads.

## Core Highlights

- **Team mode with persistent collaboration**: both Lead and Worker run as persistent ReAct loops, not one-shot request/response calls.
- **TaskBoard DAG orchestration**: task dependencies are explicitly modeled (`blockedBy` / `blocks`) with cycle detection and downstream auto-unblock.
- **Production-grade MCP tool layer**: `mcp-server` supports `STDIO`, `REMOTE_SSE`, `STREAMABLE_HTTP`, with hot provider updates and unified routing.
- **Recoverable event streaming**: built on Redis Stream + Pub/Sub, with SSE resume support via `Last-Event-ID`.
- **Skill management**: supports Skill import, enable/disable, and auto-load for scenario-based capability extension.
- **Sandbox execution**: supports session-isolated Shell/Python/filesystem tools, with artifacts syncable to MinIO.

## Architecture

![System Architecture Overview](./public/images/SystemArchitectureOverview.png)

![TaskForce Orchestration Runtime Diagram](./public/images/TaskForceOrchestrationEngineRuntimeDiagram.png)

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+
- Node.js 20+
- MySQL 8.0
- Redis 7

### Configuration Notes

- Defaults are already provided in `application.yml`, so local startup works out of the box in most cases.
- If your MySQL / Redis uses custom credentials, host, or port, override in `TaskForceBackEnd/src/main/resources/application-local.yml` and `mcp-server/src/main/resources/application-local.yml`.
- Frontend dev uses Vite proxy `/api -> http://localhost:8080` by default. If needed, update `TaskForceFrontEnd/vite.config.ts`.

### Startup Order

```bash
# 1) MCP Server (:8082)
cd mcp-server
mvn spring-boot:run

# 2) Backend (:8080)
cd ../TaskForceBackEnd
mvn spring-boot:run

# 3) Frontend (:5173)
cd ../TaskForceFrontEnd
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173), configure an LLM Provider, and start using TaskForce.

For detailed setup and operational notes, see [`QUICKSTART_EN.md`](./QUICKSTART_EN.md).

## Project Structure

```text
TaskForce/
├── TaskForceBackEnd/          # Backend service (orchestration engine + business APIs)
├── TaskForceFrontEnd/         # Frontend (Web + Electron)
├── mcp-server/                # MCP tool service
├── public/images/             # Architecture diagrams
├── QUICKSTART.md              # Chinese quick start
└── README_EN.md               # This document
```

## Tech Stack

- Backend: Spring Boot 3.3.4, Spring AI, Spring AI Alibaba, MyBatis-Plus, Redis, MySQL
- Frontend: React 19, TypeScript 5, Vite 7, TailwindCSS 4, Zustand
- MCP: Spring Boot + Spring AI MCP Client

## License

Apache License 2.0. See [`LICENSE`](./LICENSE).
