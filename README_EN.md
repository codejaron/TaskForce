# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## 🚧 Disclaimer

This project is in the early stages of development and is intended primarily for personal study and technical exploration. The code may have bugs, welcome to learn and communicate.

## Introduction
TaskForce is a **team-only multi-agent platform** centered on two runtime modes: `Team` (Lead + Workers) and `Single Chat`.

Core runtime model:

- **Team Lead**: coordinates tasks, delegates work, and reports progress
- **Worker**: executes assigned tasks with MCP tools
- **Single Chat**: direct conversation mode with one agent

## Core Features
- **Team-first orchestration**: legacy planner-worker graph orchestration removed
- **Real-time observability**: Team and Worker SSE streams for live status
- **Checkpoint persistence**: Redis-backed saver retained for Lead/Worker continuity
- **Single Chat retained**: lightweight chat path remains available
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
   - At least 1 **PLANNER** type Agent (used as Team Lead)
   - At least 1 **WORKER** type Agent (for executing tasks)
4. Configure MCP tools for Workers (optional, enhances capabilities)
5. Create a `TEAM` or `CHAT` session and start conversation

### Stop

```bash
./stop.sh
```

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
