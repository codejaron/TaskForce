# TaskForce 快速开始（本地运行）

[中文 Quick Start](./QUICKSTART.md) | [English Quick Start](./QUICKSTART_EN.md)

## 1. 目录结构（以当前仓库为准）

```text
TaskForce/
├── TaskForceBackEnd/          # Spring Boot 后端
├── TaskForceFrontEnd/         # React + Vite 前端
├── mcp-server/                # MCP Server 微服务
├── broker.conf                # RocketMQ Broker 配置文件（可选）
├── mcp-config.json            # MCP 配置（可选）
├── mcp-tools/                 # MCP 工具目录（可选）
├── .env.example               # 本地环境变量示例
└── QUICKSTART.md              # 本文档
```

## 2. 前置要求

- Java 21
- Maven 3.9+
- Node.js 20+
- npm 10+
- MySQL 8.0（默认 `3306`）
- Redis 7（默认 `6379`）
- RocketMQ NameServer（默认 `9876`）
- Nacos（默认 `8848`，如启用服务发现）

## 3. 配置（环境变量）

可选：复制根目录 `.env.example` 到 `.env` 作为本地环境变量参考。

```bash
cp .env.example .env
```

常用变量：

| 变量 | 用途 | 示例 |
|---|---|---|
| `SPRING_DATASOURCE_URL` | 后端数据库连接 | `jdbc:mysql://localhost:3306/ai_platform?...` |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 | `root` |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | `your_password` |
| `REDIS_HOST` | Redis 主机 | `localhost` |
| `REDIS_PORT` | Redis 端口 | `6379` |
| `NACOS_ADDR` | Nacos 地址 | `localhost:8848` |
| `VITE_API_BASE_URL` | 前端 API 地址 | `http://localhost:8080` |

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

## 6. FAQ / 常见问题

### 6.1 端口被占用

修改对应服务配置端口：

- 后端：`TaskForceBackEnd/src/main/resources/application.yml`
- MCP Server：`mcp-server/src/main/resources/application.yml`
- 前端：`TaskForceFrontEnd/vite.config.ts`

### 6.2 前端请求后端地址不对

在 `TaskForceFrontEnd/.env.local` 中配置：

```env
VITE_API_BASE_URL=http://localhost:8080
```

### 6.3 启动后数据库表不存在

检查以下项：

- 数据库实例已启动且账号密码正确
- 连接地址与库名正确（后端默认 `ai_platform`，MCP Server 默认 `mcp_server`）
- 初始化 SQL 配置未被关闭
