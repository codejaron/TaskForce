# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## 🚧 Disclaimer

This project is in an early stage and is mainly for learning and technical exploration. Bugs are expected.

## Introduction

TaskForce is a **multi-agent collaboration platform** centered on two runtime modes: `Team` (Lead + Workers) and `Single Chat`.

Core runtime model:

- **Team Lead**: coordinates tasks, delegates work, and reports progress
- **Worker**: executes assigned tasks with MCP tools
- **Single Chat**: direct conversation mode with one agent

## Core Features

- **Team-first orchestration**: legacy planner-worker graph orchestration removed
- **Real-time observability**: Team and Worker SSE streams for live status
- **Checkpoint persistence**: Redis-backed saver retained for Lead/Worker continuity
- **Single Chat retained**: lightweight chat path remains available
- **MCP Tool Integration**: standardized tool integration approach

📖 Detailed introduction: [blog.jarontech.top](https://blog.jarontech.top)

---

## 🚀 Quick Start (Local Run)

> This repository now supports local run only.

### 1. Start dependencies

Prepare and run these services first (default ports):

- MySQL 8.0 (`3306`)
- Redis 7 (`6379`)
- RocketMQ NameServer (`9876`)
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

Default local URLs:

- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`
- MCP Server: `http://localhost:8082`

### Initial setup

1. Open `http://localhost:5173`
2. In "Provider Management", add an LLM provider (OpenAI/Azure/Ollama, etc.)
3. In "Agent Management", create:
   - at least 1 **PLANNER** agent (used as Team Lead)
   - at least 1 **WORKER** agent
4. Configure MCP tools for workers (optional)
5. Create a `TEAM` or `CHAT` session and start conversation

## 📱 Page Screenshots

### Dashboard
![Dashboard](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.15.03@2x.png)

### Agent Workshop
![Agent Workshop](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.34.21@2x.png)

### MCP Marketplace
![MCP Tools](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.34.59@2x.png)

### Team Studio
![Team Studio](https://cdn.jsdelivr.net/gh/codejaron/image/obsidian/CleanShot%202026-01-18%20at%2019.32.40@2x.png)

## 📘 Docs

- Quick start / env vars / FAQ: [QUICKSTART_EN.md](./QUICKSTART_EN.md)

## 🏗️ Project Structure

```text
TaskForce/
├── TaskForceFrontEnd/         # React frontend
├── TaskForceBackEnd/          # Spring Boot backend
├── mcp-server/                # MCP server
├── mcp-config.json            # MCP config (optional)
├── mcp-tools/                 # MCP tools directory (optional)
├── .env.example               # Local environment variable example
└── QUICKSTART_EN.md           # English quick start
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

### MCP Server

```bash
cd mcp-server
mvn spring-boot:run
```

## Tech Stack

### Backend

- Spring Boot
- Java
- MySQL
- Redis

### Frontend

- React
- TypeScript
- Vite
- TailwindCSS
