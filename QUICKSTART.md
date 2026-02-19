# TaskForce 快速开始（本地运行）

[中文 Quick Start](./QUICKSTART.md) | [English Quick Start](./QUICKSTART_EN.md)

## 1. 目录结构（以当前仓库为准）

```text
TaskForce/
├── TaskForceBackEnd/          # Spring Boot 后端
├── TaskForceFrontEnd/         # React + Vite 前端
├── mcp-server/                # MCP Server 微服务
├── mcp-config.json            # MCP 配置（可选）
├── mcp-tools/                 # MCP 工具目录（可选）
└── QUICKSTART.md              # 本文档
```

## 2. 前置要求

- Java 21
- Maven 3.9+
- Node.js 20+
- npm 10+
- MySQL 8.0（默认 `3306`）
- Redis 7（默认 `6379`）
- Nacos（默认 `8848`，如启用服务发现）

## 3. 配置（按需）

默认值已在 `application.yml` 提供，通常可直接启动。

如果你的 MySQL / Redis 有密码、账号或非默认地址端口，请在以下文件覆盖：

- `TaskForceBackEnd/src/main/resources/application-local.yml`
- `mcp-server/src/main/resources/application-local.yml`

常见需要覆盖的项：

- `spring.datasource.*`
- `spring.data.redis.*`
- `sandbox.sync.minio.*`（按需）

## 4. 启动顺序

### 4.1 启动 MCP Server

```bash
cd mcp-server
mvn spring-boot:run
```

默认地址：`http://localhost:8082`

### 4.2 启动后端（Spring Boot）

```bash
cd TaskForceBackEnd
mvn spring-boot:run
```

默认地址：`http://localhost:8080`

### 4.3 启动前端（Vite）

```bash
cd TaskForceFrontEnd
npm install
npm run dev
```

默认地址：`http://localhost:5173`

## 5. 常用本地命令

```bash
# 后端
cd TaskForceBackEnd
mvn test

# 前端
cd TaskForceFrontEnd
npm run lint
npm run build

# MCP Server
cd mcp-server
mvn test
```
