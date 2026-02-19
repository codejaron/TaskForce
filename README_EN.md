# TaskForce

[中文 README](./README.md) | [English README](./README_EN.md)

## Overview

TaskForce is a local-first multi-agent orchestration system built with a pure Java/Spring stack.  
Its core model is a persistent `Team Lead + Worker` collaboration loop for real task orchestration, tool execution, and observable runtime control.

## Core Highlights (Code-Based)

### 1. `mcp-server` is production-grade, not a demo bridge

- Triple provider protocols: `STDIO`, `REMOTE_SSE`, `STREAMABLE_HTTP`
- Protocol-aware provider connection pools with per-type defaults, max cap, and acquire timeout
- Unified routing for both native tools (`@Tool`, `@McpTool`) and external MCP providers via `ToolRouter`
- Hot-plug provider lifecycle (add/delete/reload) with cross-instance sync through Redis Pub/Sub
- Also exposed as an MCP provider itself via `/mcp` (Streamable HTTP) and `/mcp/sse`

### 2. Redis Stream-backed SSE event bus with replay support

- Event infrastructure uses `Redis Stream + Pub/Sub` instead of in-memory SSE broadcasting
- SSE endpoints accept `Last-Event-ID` for resume/replay
- Redis stream record IDs are attached to events for precise continuation
- Supports both Team-level and Worker-level event streams

### 3. Team Lead is a message-driven persistent ReAct loop

- Lead loop is stateful (`EXECUTING / WAITING_REPLY / IDLE / COMPLETED / FAILED`), not a one-shot LLM call
- Inbox and task-board events can wake waiting lead loops
- Scheduling decisions evaluate inbox, dispatchable tasks, and in-flight workers before continue/wait

### 4. TaskBoard is DAG orchestration with atomic state transitions

- Task model keeps explicit dependency edges (`blockedBy`, `blocks`)
- DAG validation includes cycle detection (DFS)
- Redis Lua scripts perform atomic completion + downstream unblocking
- Task lifecycle events are emitted through the unified event bus

### 5. Distributed deployment is first-class

- Session owner election via Redis + Redisson lock + owner TTL renewal
- Non-owner nodes forward Team APIs to the owner node
- Integration tests cover concurrent owner acquisition, forwarding, and sequence uniqueness

### 6. Native Java/Spring ecosystem advantage

- Pure Spring stack: Spring Boot 3 + Spring AI + Spring AI Alibaba
- Enterprise-ready integrations: MyBatis-Plus, Redis, Nacos, Druid, Redisson
- No Python runtime dependency and no Java wrapper over Python internals

## Quick Start

For startup steps, see:

- Chinese: [`QUICKSTART.md`](./QUICKSTART.md)
- English: [`QUICKSTART_EN.md`](./QUICKSTART_EN.md)

## Modules

- `TaskForceFrontEnd`: React + Vite console
- `TaskForceBackEnd`: team orchestration, runtime state, event bus, persistence
- `mcp-server`: MCP tool aggregation layer (Native / STDIO / Remote SSE / Streamable HTTP)

## Documentation

- MCP Server docs: [`mcp-server/README.md`](./mcp-server/README.md)
- Project blog: [blog.jarontech.top](https://blog.jarontech.top)

## License

Apache License 2.0 (see [`LICENSE`](./LICENSE))
