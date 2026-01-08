# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

> 🚧 **Project Status: Early Development**
> 
> This project is currently in its **initial stages** and is created primarily for **learning and experimentation purposes**.
> Expect bugs and unstable features. It is **not intended for production use**.

A smart chat/agent platform built with **Spring Boot** and **React + Vite**, supporting MCP tool integration.

## Table of Contents

- [GroupChat Workflow](#groupchat-workflow)
  - [Execution Flow](#execution-flow)
  - [Key Features](#key-features)
- [Context Management: Artifact System](#context-management-artifact-system)
  - [How Artifact System Works](#how-artifact-system-works)
  - [Context Composition](#context-composition)
- [Quick Start (Docker one-command)](#-quick-start-docker-one-command)
- [Docs](#-docs)
- [Project Structure](#-project-structure)
- [Development](#-development)
- [Tech Stack](#tech-stack)

## GroupChat Workflow

TaskForce uses a **Plan-Execute asynchronous architecture** for intelligent task orchestration.

### Execution Flow

```
User Input → Create Session
    ↓
PlannerAgent analyzes requirements
    ↓
Generate ExecutionPlan → Store to execution_plan table
    ↓                           ↓
Display plan ← PlanGeneratedEvent (to frontend)
    ↓
WorkflowEngine executes asynchronously
    ↓
StepExecutor executes each step
    ↓
Invoke Worker (with MCP tools)
    ↓
Worker output → Extract Artifact → session_artifact table
    ↓                                       ↓
EventBus pushes events            Used by subsequent steps
    ↓
Frontend real-time update (SSE stream)
    ↓
All steps complete → SessionCompleteEvent
```

### Key Features

1. **Fire-and-forget execution**: HTTP request returns immediately, doesn't block waiting for completion
2. **Event-driven communication**: Frontend and backend sync in real-time via independent SSE connection
3. **Automatic error recovery**: Triggers ReplannerAgent for automatic fix on errors
4. **Structured context**: Passes structured data between steps via Artifact system
5. **Pausable/Resumable**: Automatically pauses when user input needed, continues after receiving input

---

## Context Management: Artifact System

TaskForce uses an **Artifact system** for session-level context management.

### How Artifact System Works

#### 1. Storage Structure
- **Database table**: `session_artifact`
- **Fields**:
  - `session_id`: Session ID
  - `artifact_key`: Data identifier (e.g., "PLAN", "search_results", "generated_code")
  - `artifact_value`: LONGTEXT content
  - Unique constraint: `(session_id, artifact_key)`

#### 2. XML Tag Format
Workers output structured data using XML tags:
```xml
<artifact key="search_results">
1. Search Result A
2. Search Result B
</artifact>

<artifact key="generated_code">
public class Example {
    // code content
}
</artifact>
```

#### 3. Context Passing Flow
```
PlannerAgent generates ExecutionPlan
    ↓
Store to execution_plan table (JSON format)
    ↓
WorkflowEngine starts execution
    ↓
StepExecutor builds TaskContext:
  - userGoal (from ExecutionPlan.goal)
  - recentHistory (last 3 messages)
  - sharedData (existing artifacts)
  - currentStep (current step info)
    ↓
Worker A executes Step 1
    ↓
Outputs result with <artifact> tags
    ↓
StepExecutor extracts and saves to session_artifact table
    ↓
Worker B executes Step 2
    ↓
Reads Worker A's artifacts via TaskContext.sharedData
    ↓
Continues execution...
```

### Context Composition

#### PlannerAgent Context
- User-provided goal description
- Available Workers list (with their tool capabilities)

#### Worker Context
- **User goal**: Overall objective of the session
- **Current step description**: Specific task assigned by PlannerAgent
- **Shared data**: All generated Artifacts
- **Recent conversation history**: Last 3 messages (for context understanding)

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
