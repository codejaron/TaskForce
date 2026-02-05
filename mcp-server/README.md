# MCP Server 微服务

统一的 MCP (Model Context Protocol) Server 微服务，支持三种工具来源：

1. **STDIO 工具** - 通过 npx 等子进程启动的工具（如 weather、filesystem）
2. **Remote SSE 工具** - 远程 SSE 服务（如 n8n 集成的工具）

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                   MCP Server 微服务                          │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐   │
│  │                对外统一 SSE 接口                      │   │
│  │  GET  /mcp/sse     → SSE 长连接                      │   │
│  │  POST /mcp/message → 接收请求, 路由到对应工具          │   │
│  │  GET  /api/tools   → 返回所有可用工具列表              │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│                           ▼                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  ToolRouter                          │   │
│  │           根据 tool_name 路由到对应 Provider          │   │
│  └─────────────────────────────────────────────────────┘   │
│                    │                 │                      │
│                    ▼                 ▼                      │
│         ┌───────────────┐     ┌─────────────┐              │
│         │StdioProvider  │     │RemoteSse    │              │
│         │               │     │Provider     │              │
│         │ 管理 npx      │     │             │              │
│         │ 子进程        │     │ 转发到远程  │              │
│         │               │     │ SSE 服务    │              │
│         │- weather      │     │- n8n_gmail  │              │
│         │- filesystem   │     │- cloud_x    │              │
│         └───────────────┘     └─────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

## 快速开始

### 开发环境

```bash
cd mcp-server
mvn spring-boot:run
```

服务启动后访问：
- SSE 端点: http://localhost:8081/mcp/sse
- 工具列表: http://localhost:8081/api/tools
- 健康检查: http://localhost:8081/actuator/health

### Docker 部署

```bash
cd mcp-server
docker build -t mcp-server .
docker run -p 8081:8081 mcp-server
```

## 配置说明

### 配置来源

通过环境变量 `MCP_CONFIG_SOURCE` 控制：
- `database` - 从数据库加载配置
- `file` - 从 JSON 配置文件加载
- `both` - 同时从数据库和配置文件加载

### JSON 配置文件格式

```json
{
  "providers": {
    "weather": {
      "type": "STDIO",
      "enabled": true,
      "description": "Weather tools",
      "command": "npx",
      "args": ["-y", "@anthropics/weather-server"],
      "env": {}
    },
    "n8n_gmail": {
      "type": "REMOTE_SSE",
      "enabled": true,
      "sseUrl": "https://your-n8n-instance.com/mcp/sse",
      "headers": {
        "Authorization": "Bearer ${N8N_API_KEY}"
      },
      "timeout": 60
    }
  }
}
```

## API 接口

### MCP 协议接口

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | /mcp/sse | SSE 长连接 |
| POST | /mcp/message | JSON-RPC 消息处理 |

### REST API 接口

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | /api/tools | 获取所有工具列表 |
| GET | /api/tools/{name} | 获取单个工具定义 |
| POST | /api/tools/call | 调用工具 |
| GET | /api/tools/stats | 获取统计信息 |
| GET | /api/providers | 获取提供者列表 |
| POST | /api/providers | 添加提供者 |
| DELETE | /api/providers/{id} | 删除提供者 |
| POST | /api/providers/test | 测试连接 |
| POST | /api/providers/reload | 重新加载所有提供者 |

## 添加新工具

### 1. STDIO 工具

在配置文件或数据库中添加：

```json
{
  "type": "STDIO",
  "command": "npx",
  "args": ["-y", "@your-org/your-mcp-server"],
  "env": {
    "API_KEY": "${YOUR_API_KEY}"
  }
}
```

### 2. Remote SSE 工具

```json
{
  "type": "REMOTE_SSE",
  "sseUrl": "https://remote-mcp-server.com/sse",
  "headers": {
    "Authorization": "Bearer your-token"
  },
  "timeout": 60
}
```

## 环境变量

| 变量 | 默认值 | 描述 |
|------|--------|------|
| MCP_CONFIG_SOURCE | file | 配置来源 |
| MCP_CONFIG_PATH | ./mcp-server-config.json | 配置文件路径 |
| DB_URL | jdbc:h2:mem:mcpserver | 数据库 URL |
| DB_USERNAME | sa | 数据库用户名 |
| DB_PASSWORD | - | 数据库密码 |

## 与主服务集成

MCP Server 作为独立微服务运行，主服务（TaskForce Backend）通过以下方式使用：

1. **作为 Remote SSE 客户端**：主服务连接到 MCP Server 的 `/mcp/sse` 端点
2. **通过 REST API**：主服务调用 `/api/tools/call` 执行工具

## License

MIT
