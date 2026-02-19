# MCP Server 微服务

TaskForce 的统一工具网关服务，负责聚合 **Native 工具** 与 **外部 MCP Provider**，并对外提供标准 MCP 协议入口。

## 定位

`mcp-server` 在当前项目中承担三件事：

- 聚合工具：把 Native 工具和外部 Provider 工具统一到一个路由层
- 统一调用：无论来源，统一通过 `ToolRouter` 调度
- 对外暴露：支持 MCP `Streamable HTTP` 与 `SSE` 两种接入方式

## 当前能力

- Provider 类型：`STDIO`、`REMOTE_SSE`、`STREAMABLE_HTTP`
- Native 工具扫描：基于 `@Tool` / `@McpTool` 自动注册
- 内置 Native 工具：`native::websearch`、`native::webfetch`
- Provider 热更新：新增/删除/重载后实时生效
- 多实例同步：Provider 变更通过 Redis Pub/Sub 广播
- 客户端连接池：按 Provider 类型分配默认池大小并统一限流

## 架构要点

- 核心路由组件：`ToolRouter`
- 配置来源：数据库表 `tool_provider_config`（`schema.sql` 自动初始化）
- Native 工具前缀：`native::toolName`
- 外部工具前缀：`providerName::toolName`

## 快速启动

### 1. 前置依赖

- Java 21
- Maven 3.9+
- MySQL 8.0（默认库：`mcp_server`）
- Redis 7

### 2. 配置

默认值已在 `application.yml` 提供。  
如果 MySQL / Redis 有账号密码或非默认地址端口，请在以下文件覆盖：

- `mcp-server/src/main/resources/application-local.yml`

常见覆盖项：

- `spring.datasource.*`
- `spring.data.redis.*`
- `mcp.client-pool.*`
- `mcp.provider-sync.*`

### 3. 启动

```bash
cd mcp-server
mvn spring-boot:run
```

默认地址：`http://localhost:8082`

### 4. 健康检查

- `GET /actuator/health`

## API 总览

### MCP 协议接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/mcp` | Streamable HTTP（JSON-RPC） |
| `GET` | `/mcp/sse` | SSE 长连接通道 |
| `POST` | `/mcp/message` | SSE 模式下的 JSON-RPC 消息入口（可带 `sessionId`） |

当前协议处理方法：`initialize`、`initialized`、`tools/list`、`tools/call`、`ping`。

### REST 管理接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/tools` | 列出所有工具（含 Native + 外部） |
| `GET` | `/api/tools/{name}` | 查询单个工具 |
| `POST` | `/api/tools/call` | 调用工具（通用） |
| `POST` | `/api/tools/{name}/invoke` | 快速调用某个工具 |
| `GET` | `/api/tools/stats` | 工具与 Provider 统计 |
| `GET` | `/api/providers` | 列出 Provider |
| `GET` | `/api/providers/{providerId}/tools` | 查询某个 Provider 的工具 |
| `POST` | `/api/providers` | 新增 Provider |
| `DELETE` | `/api/providers/{providerId}` | 删除 Provider |
| `POST` | `/api/providers/test` | 测试 Provider 连接 |
| `POST` | `/api/providers/reload` | 重载所有 Provider |

## Provider 配置示例

> 所有 Provider 配置通过 `/api/providers` 写入数据库。

### 1. STDIO

```json
{
  "name": "filesystem",
  "type": "STDIO",
  "enabled": true,
  "description": "Local filesystem tools",
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
  "env": {}
}
```

### 2. REMOTE_SSE

```json
{
  "name": "remote_search",
  "type": "REMOTE_SSE",
  "enabled": true,
  "description": "Remote SSE MCP provider",
  "sseUrl": "https://example.com/mcp/sse",
  "timeout": 30
}
```

### 3. STREAMABLE_HTTP

```json
{
  "name": "remote_http",
  "type": "STREAMABLE_HTTP",
  "enabled": true,
  "description": "Remote HTTP MCP provider",
  "httpUrl": "https://example.com/mcp",
  "timeout": 30
}
```

## 与主服务协作

`TaskForceBackEnd` 通过 MCP 协议调用本服务暴露的工具能力；本服务负责：

- 管理 Provider 生命周期
- 聚合并路由工具调用
- 返回统一的工具执行结果结构

## License

Apache License 2.0（见仓库根目录 `LICENSE`）。
