# TaskForce 快速开始（Docker / 本地开发）

[中文 Quick Start](./QUICKSTART.md) | [English Quick Start](./QUICKSTART_EN.md)


## 1. 目录结构（以当前仓库为准）

```text
TaskForce/
├── TaskForceBackEnd/          # Spring Boot 后端
├── TaskForceFrontEnd/         # React + Vite 前端
├── docker-compose.yml         # Compose 编排（MySQL/Redis/RocketMQ/后端/前端）
├── broker.conf                # RocketMQ Broker 配置文件
├── mcp-config.json            # MCP 配置（会挂载进后端容器）
├── mcp-tools/                 # MCP 工具目录（会挂载进后端容器）
├── start.sh                   # 一键启动（自动生成 .env）
├── stop.sh                    # 停止（可选择是否删数据卷）
├── .env.example               # 环境变量模板
└── QUICKSTART.md              # 本文档
```

## 2. Docker 一键启动（推荐）

### 2.1 前置要求

- Docker 20.10+
- Docker Compose 2+
- 建议 4GB+ 可用内存、10GB+ 磁盘空间

### 2.2 启动

```bash
./start.sh
```

启动完成后常用地址：

- 前端：http://localhost:3000 （默认）
- 后端：http://localhost:8080 （默认）
- RocketMQ Dashboard：http://localhost:18080 （默认）

### 2.3 停止

```bash
./stop.sh
```

## 3. 配置（环境变量）

项目的 Docker 配置主要通过根目录的 `.env` 管理。

- 如果你用 `./start.sh` 启动：首次会自动从 `.env.example` 生成 `.env`。
- 如果你直接使用 `docker compose ...`：请确保根目录存在 `.env`（或在 shell 环境里导出对应变量）。

手动创建/更新 `.env`：

```bash
cp .env.example .env
```

| 变量 | 用途 | 默认/示例 | 影响范围 |
|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `TaskForce123456`（示例） | MySQL 容器 + 后端连接密码 |
| `MYSQL_PORT` | MySQL 映射到宿主机的端口 | `3306` | 仅端口映射 |
| `REDIS_PORT` | Redis 映射到宿主机的端口 | `6379` | 仅端口映射 |
| `ROCKETMQ_NAMESRV_PORT` | RocketMQ NameServer 端口 | `9876` | 仅端口映射 |
| `ROCKETMQ_BROKER_PORT` | RocketMQ Broker 端口 | `10911` | 仅端口映射 |
| `ROCKETMQ_DASHBOARD_PORT` | RocketMQ Dashboard 端口 | `18080` | 仅端口映射 |
| `BACKEND_PORT` | 后端映射到宿主机端口 | `8080` | 端口映射 |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `local` | 后端运行配置 |
| `JAVA_OPTS` | JVM 启动参数 | `-Xms512m -Xmx1024m` | 后端容器 JVM |
| `FRONTEND_PORT` | 前端映射到宿主机端口 | `3000` | 端口映射（容器内 nginx:80） |
| `VITE_API_BASE_URL` | 前端打包时的 API Base URL | `http://localhost:8080` | 前端构建产物 |

## 4. 本地开发（不使用 Docker）

> 适合需要热更新、断点调试。

### 4.1 后端（Spring Boot）

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

> 如果你使用 Docker 的 MySQL，后端需要确保连接地址指向 `localhost`（而不是 compose 内网的 `mysql`）。具体看 `application-local.yml` 如何配置。

### 4.2 前端（Vite）

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

前端 API 地址通常由 `VITE_API_BASE_URL` 决定：

- Docker 构建时通过 `docker-compose.yml` 的 `build.args` 注入
- 本地开发时可以在前端目录创建 `.env.local`（Vite 惯例）来覆盖，例如：
  - `VITE_API_BASE_URL=http://localhost:8080`

## 5. 常用命令

> `start.sh` 会自动选择 `docker compose` 或 `docker-compose`。你手动执行时请按你本机的实际命令为准。

```bash
# 查看状态
docker compose ps

# 查看日志
docker compose logs -f

# 重启后端
docker compose restart backend

# 进入后端容器
docker compose exec backend /bin/sh
```

## 6. FAQ / 常见问题

### 6.1 端口被占用

修改根目录 `.env` 里的端口，例如：

- `FRONTEND_PORT=3001`
- `BACKEND_PORT=8081`

然后重新启动：

```bash
./stop.sh
./start.sh
```

### 6.2 前端请求的后端地址不对

- Docker 部署：修改根目录 `.env` 的 `VITE_API_BASE_URL`，然后重新构建前端镜像（`./start.sh` 或 `docker compose build frontend --no-cache`）。
- 本地开发：在 `TaskForceFrontEnd/.env.local` 配置 `VITE_API_BASE_URL`。

