# TaskForce Quick Start (Docker / Local Development)

[中文 Quick Start](./QUICKSTART.md) | [English Quick Start](./QUICKSTART_EN.md)


## Table of Contents

- [Repository Layout](#repository-layout)
- [Docker One-Command Startup (Recommended)](#docker-one-command-startup-recommended)
  - [Prerequisites](#prerequisites)
  - [Start](#start)
  - [Stop](#stop)
- [Configuration (Environment Variables)](#configuration-environment-variables)
- [Local Development (Without Docker)](#local-development-without-docker)
  - [Backend (Spring Boot)](#backend-spring-boot)
  - [Frontend (Vite)](#frontend-vite)
- [Common Docker Commands](#common-docker-commands)
- [FAQ](#faq)
  - [Port Already In Use](#port-already-in-use)
  - [Frontend Requests the Wrong Backend URL](#frontend-requests-the-wrong-backend-url)

## Repository Layout

```text
TaskForce/
├── TaskForceBackEnd/          # Spring Boot backend
├── TaskForceFrontEnd/         # React + Vite frontend
├── docker-compose.yml         # Compose (MySQL/Redis/RocketMQ/backend/frontend)
├── broker.conf                # RocketMQ Broker configuration
├── mcp-config.json            # MCP config (mounted into backend container)
├── mcp-tools/                 # MCP tools directory (mounted into backend container)
├── start.sh                   # One-command startup (auto-generates .env)
├── stop.sh                    # Stop (can optionally remove volumes)
├── .env.example               # Environment variables template
└── QUICKSTART.md              # Chinese quick start
```

## Docker One-Command Startup (Recommended)

### Prerequisites

- Docker 20.10+
- Docker Compose 2+
- Recommended: 4GB+ free memory, 10GB+ free disk

### Start

```bash
./start.sh
```

Common URLs after startup:

- Frontend: http://localhost:3000 (default)
- Backend: http://localhost:8080 (default)
- RocketMQ Dashboard: http://localhost:18080 (default)

### Stop

```bash
./stop.sh
```

## Configuration (Environment Variables)

The Docker setup is primarily controlled by the root `.env` file.

- If you start with `./start.sh`: it will generate `.env` from `.env.example` on first run.
- If you run `docker compose ...` directly: make sure `.env` exists in the repository root (or export the env vars in your shell).

Create/update `.env` manually:

```bash
cp .env.example .env
```

| Variable | Purpose | Default / Example | Affects |
|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root password | `TaskForce123456` (example) | MySQL container + backend DB password |
| `MYSQL_PORT` | MySQL port mapped to host | `3306` | Port mapping only |
| `REDIS_PORT` | Redis port mapped to host | `6379` | Port mapping only |
| `ROCKETMQ_NAMESRV_PORT` | RocketMQ NameServer port | `9876` | Port mapping only |
| `ROCKETMQ_BROKER_PORT` | RocketMQ Broker port | `10911` | Port mapping only |
| `ROCKETMQ_DASHBOARD_PORT` | RocketMQ Dashboard port | `18080` | Port mapping only |
| `BACKEND_PORT` | Backend port mapped to host | `8080` | Port mapping |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `local` | Backend runtime config |
| `JAVA_OPTS` | JVM args | `-Xms512m -Xmx1024m` | Backend container JVM |
| `FRONTEND_PORT` | Frontend port mapped to host | `3000` | Port mapping (nginx:80 inside container) |
| `VITE_API_BASE_URL` | API Base URL during frontend build | `http://localhost:8080` | Frontend build artifact |

## Local Development (Without Docker)

> Best for hot reload and debugging.

### Backend (Spring Boot)

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

> If you use MySQL from Docker, make sure the backend points to `localhost` (not the compose internal hostname `mysql`). See how `application-local.yml` is configured.

### Frontend (Vite)

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

The frontend API base is usually controlled by `VITE_API_BASE_URL`:

- In Docker builds: injected via `docker-compose.yml` `build.args`
- In local development: you can create `TaskForceFrontEnd/.env.local` (Vite convention), e.g.
  - `VITE_API_BASE_URL=http://localhost:8080`

## Common Docker Commands

> `start.sh` will automatically choose `docker compose` or `docker-compose`. When running manually, use the one available on your machine.

```bash
# Show status
docker compose ps

# Follow logs
docker compose logs -f

# Restart backend
docker compose restart backend

# Shell into backend container
docker compose exec backend /bin/sh
```

## FAQ

### Port Already In Use

Edit ports in the root `.env`, for example:

- `FRONTEND_PORT=3001`
- `BACKEND_PORT=8081`

Then restart:

```bash
./stop.sh
./start.sh
```

### Frontend Requests the Wrong Backend URL

- Docker deployment: update `VITE_API_BASE_URL` in the root `.env`, then rebuild the frontend image (`./start.sh` or `docker compose build frontend --no-cache`).
- Local development: set `VITE_API_BASE_URL` in `TaskForceFrontEnd/.env.local`.
