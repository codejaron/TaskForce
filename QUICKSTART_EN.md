# TaskForce Quick Start (Local Run)

[中文 Quick Start](./QUICKSTART.md) | [English Quick Start](./QUICKSTART_EN.md)

## Table of Contents

- [Repository Layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Configuration (Optional)](#configuration-optional)
- [Startup Order](#startup-order)
- [Common Local Commands](#common-local-commands)

## Repository Layout

```text
TaskForce/
├── TaskForceBackEnd/          # Spring Boot backend
├── TaskForceFrontEnd/         # React + Vite frontend
├── mcp-server/                # MCP server microservice
├── mcp-config.json            # MCP config (optional)
├── mcp-tools/                 # MCP tools directory (optional)
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

## Configuration (Optional)

Defaults in `application.yml` are usually enough to start locally.

If your MySQL / Redis requires credentials or non-default host/port, override in:

- `TaskForceBackEnd/src/main/resources/application-local.yml`
- `mcp-server/src/main/resources/application-local.yml`

Common override keys:

- `spring.datasource.*`
- `spring.data.redis.*`
- `sandbox.sync.minio.*` (optional)

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
