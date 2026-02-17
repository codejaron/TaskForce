# TaskForce Quick Start (Local Run)

[中文 Quick Start](./QUICKSTART.md) | [English Quick Start](./QUICKSTART_EN.md)

## Table of Contents

- [Repository Layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Configuration (Environment Variables)](#configuration-environment-variables)
- [Startup Order](#startup-order)
- [Common Local Commands](#common-local-commands)
- [FAQ](#faq)

## Repository Layout

```text
TaskForce/
├── TaskForceBackEnd/          # Spring Boot backend
├── TaskForceFrontEnd/         # React + Vite frontend
├── mcp-server/                # MCP server microservice
├── mcp-config.json            # MCP config (optional)
├── mcp-tools/                 # MCP tools directory (optional)
├── .env.example               # Local environment variable example
└── QUICKSTART_EN.md           # This document
```

## Prerequisites

- Java 21
- Maven 3.9+
- Node.js 20+
- npm 10+
- MySQL 8.0 (default `3306`)
- Redis 7 (default `6379`)
- Nacos (default `8848`, if service discovery is enabled)

## Configuration (Environment Variables)

Optional: copy `.env.example` to `.env` as local reference.

```bash
cp .env.example .env
```

Common variables:

| Variable | Purpose | Example |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Backend DB URL | `jdbc:mysql://localhost:3306/ai_platform?...` |
| `SPRING_DATASOURCE_USERNAME` | DB username | `root` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | `your_password` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `NACOS_ADDR` | Nacos address | `localhost:8848` |
| `VITE_API_BASE_URL` | Frontend API base URL | `http://localhost:8080` |

## Startup Order

### 1. Start MCP Server

```bash
cd mcp-server
mvn spring-boot:run
```

Default URL: `http://localhost:8082`

### 2. Start backend

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

Default URL: `http://localhost:8080`

### 3. Start frontend

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

Default URL: `http://localhost:5173`

## Common Local Commands

```bash
# Backend
cd TaskForceBackEnd
mvn test

# Frontend
cd TaskForceFrontEnd
npm run lint
npm run build

# MCP Server
cd mcp-server
mvn test
```

## FAQ

### Port already in use

Update ports in:

- backend: `TaskForceBackEnd/src/main/resources/application.yml`
- MCP server: `mcp-server/src/main/resources/application.yml`
- frontend: `TaskForceFrontEnd/vite.config.ts`

### Frontend points to the wrong backend URL

Set this in `TaskForceFrontEnd/.env.local`:

```env
VITE_API_BASE_URL=http://localhost:8080
```

### Missing tables after startup

Check:

- DB service is running and credentials are correct
- DB URL/database names are correct (`ai_platform` for backend, `mcp_server` for MCP server)
- SQL init settings are enabled
