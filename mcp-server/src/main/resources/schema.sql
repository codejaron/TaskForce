-- MCP Server 数据库初始化脚本

-- 工具提供者配置表
CREATE TABLE IF NOT EXISTS tool_provider_config (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL COMMENT 'STDIO, NATIVE, REMOTE_SSE',
    enabled BOOLEAN DEFAULT TRUE,
    description TEXT,
    
    -- STDIO 类型配置
    command VARCHAR(255) COMMENT '启动命令',
    args TEXT COMMENT '命令参数 (JSON 数组)',
    env TEXT COMMENT '环境变量 (JSON 对象)',
    
    -- NATIVE 类型配置
    bean_name VARCHAR(255) COMMENT 'Spring Bean 名称',
    class_name VARCHAR(512) COMMENT '工具类全限定名',
    
    -- REMOTE_SSE 类型配置
    sse_url VARCHAR(1024) COMMENT '远程 SSE 服务 URL',
    headers TEXT COMMENT '请求头 (JSON 对象)',
    timeout INT DEFAULT 30 COMMENT '超时时间（秒）',
    
    -- 状态字段
    connected BOOLEAN DEFAULT FALSE COMMENT '连接状态',
    tool_count INT DEFAULT 0 COMMENT '工具数量',
    last_connected_at TIMESTAMP COMMENT '最后连接时间',
    error_message TEXT COMMENT '错误信息',
    
    -- 时间戳
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_type (type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具提供者配置表';
