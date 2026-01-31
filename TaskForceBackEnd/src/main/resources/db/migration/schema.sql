-- ========================================
-- TaskForce - Complete Database Schema
-- Version: 2.8 (Added status column to messages table)
-- Date: 2026-01-31
-- ========================================

-- ========================================
-- 1. LLM 模型渠道表
-- ========================================
CREATE TABLE IF NOT EXISTS llm_providers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '渠道名称(用户自定义)',
    type VARCHAR(50) NOT NULL COMMENT '厂商类型: OPENAI/AZURE/OLLAMA/CUSTOM',
    base_url VARCHAR(500) NOT NULL COMMENT 'API基础URL',
    api_key VARCHAR(1000) COMMENT 'API密钥(AES加密)',
    config JSON COMMENT '扩展配置(JSON格式)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LLM模型渠道表';

-- ========================================
-- 2. 渠道模型子表
-- ========================================
CREATE TABLE IF NOT EXISTS channel_models (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel_id BIGINT NOT NULL COMMENT '关联主表 ID (llm_providers.id)',
    model_value VARCHAR(100) NOT NULL COMMENT '真实模型标识(API调用值，例如 gpt-4o 或部署名)',
    display_name VARCHAR(100) NOT NULL COMMENT '展示给用户看的名字，例如 GPT-4o (公司)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_channel_id (channel_id),
    FOREIGN KEY (channel_id) REFERENCES llm_providers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道模型子表';

-- ========================================
-- 3. 智能体表
-- ========================================
CREATE TABLE IF NOT EXISTS agents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id BIGINT COMMENT '关联的渠道ID',
    name VARCHAR(100) NOT NULL COMMENT '智能体名称',
    system_prompt TEXT COMMENT '系统提示词',
    model VARCHAR(100) NULL COMMENT 'Agent 指定的模型标识(例如 gpt-4o 或自定义部署名)',
    temperature DECIMAL(3,2) DEFAULT 0.70 COMMENT '温度参数',
    max_tokens INT DEFAULT 4096 COMMENT '最大Token数',
    description TEXT COMMENT '描述',
    role_type VARCHAR(20) DEFAULT 'WORKER' COMMENT '角色类型: WORKER/PLANNER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_provider_id (provider_id),
    INDEX idx_role_type (role_type),
    FOREIGN KEY (provider_id) REFERENCES llm_providers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体表';

-- ========================================
-- 4. Agent MCP工具关联表
-- ========================================
CREATE TABLE IF NOT EXISTS agent_tools (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    tool_id VARCHAR(200) NOT NULL COMMENT '工具ID (格式: serverId::toolName)',
    enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用（支持临时禁用工具）',
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_agent_id (agent_id),
    INDEX idx_tool_id (tool_id),
    UNIQUE KEY uk_agent_tool (agent_id, tool_id),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent MCP工具关联表';

-- ========================================
-- 5. 会话表
-- ========================================
CREATE TABLE IF NOT EXISTS sessions (
    id VARCHAR(50) PRIMARY KEY COMMENT '会话ID(UUID)',
    name VARCHAR(200) COMMENT '会话名称',
    type VARCHAR(20) NOT NULL COMMENT '类型: SINGLE/GROUP',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/PAUSED/COMPLETED',
    config JSON COMMENT '会话配置',
    max_rounds INT DEFAULT 10 COMMENT '最大轮次',
    current_round INT DEFAULT 0 COMMENT '当前轮次',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (type),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- ========================================
-- 6. 会话-智能体关联表
-- ========================================
CREATE TABLE IF NOT EXISTS session_agents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(50) NOT NULL COMMENT '会话ID',
    agent_id BIGINT NOT NULL COMMENT '智能体ID',
    role_in_session VARCHAR(50) COMMENT '会话中的角色',
    is_admin BOOLEAN DEFAULT FALSE COMMENT '是否为管理员/群主',
    join_order INT COMMENT '加入顺序',
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_agent_id (agent_id),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    UNIQUE KEY uk_session_agent (session_id, agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话智能体关联表';

-- ========================================
-- 7. 消息表
-- ========================================
CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(50) NOT NULL COMMENT '会话ID',
    agent_id BIGINT COMMENT '发送者Agent ID(NULL表示用户发送)',
    agent_name VARCHAR(255) COMMENT 'Agent名称（冗余字段，便于查询）',
    content TEXT NOT NULL COMMENT '消息内容',
    message_type VARCHAR(20) DEFAULT 'text' COMMENT '消息类型: text/tool_use/tool_result',
    role VARCHAR(20) NOT NULL COMMENT '角色: user/assistant/system',
    tool_name VARCHAR(100) COMMENT '工具名称(如果是工具调用)',
    tool_args JSON COMMENT '工具参数',
    tool_result TEXT COMMENT '工具执行结果',
    sequence INT COMMENT '消息序号',
    status VARCHAR(20) DEFAULT 'COMPLETED' COMMENT '消息状态: STREAMING-流式输出中, COMPLETED-已完成',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_created_at (created_at),
    INDEX idx_session_sequence (session_id, sequence),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息表';

-- ========================================
-- 8. 会话Artifact存储表
-- ========================================
CREATE TABLE IF NOT EXISTS session_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    artifact_key VARCHAR(128) NOT NULL COMMENT 'Artifact键名',
    artifact_value LONGTEXT COMMENT 'Artifact值（支持大文本）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_session_key (session_id, artifact_key),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    INDEX idx_session_id (session_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话Artifact存储表-XML上下文管理';

-- ========================================
-- 9. 执行计划表（异步工作流状态）
-- ========================================
CREATE TABLE IF NOT EXISTS execution_plan (
    plan_id VARCHAR(64) PRIMARY KEY COMMENT '计划ID(UUID)',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    goal TEXT COMMENT '用户目标',
    status VARCHAR(32) NOT NULL COMMENT '状态: PLANNING/EXECUTING/REPLANNING/PAUSED/COMPLETED/FAILED',
    current_step_index INT DEFAULT 0 COMMENT '当前执行步骤索引',
    pause_reason VARCHAR(64) COMMENT '暂停原因: waiting_user/blocked/replan_limit',
    paused_by VARCHAR(32) COMMENT '暂停触发源: PLANNER/WORKER/USER/BLOCKED',
    paused_at_step_index INT COMMENT 'Worker澄清时记录的步骤索引',
    paused_agent_id VARCHAR(64) COMMENT 'Worker澄清时记录的Agent ID',
    pending_question TEXT COMMENT '待用户回答的问题',
    replan_count INT DEFAULT 0 COMMENT '重规划次数',
    steps_json JSON COMMENT '步骤列表(JSON格式)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_status (status),
    INDEX idx_paused_by (paused_by),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行计划表-异步工作流状态管理';

-- ========================================
-- 10. Token 使用统计表（计费用）
-- ========================================
CREATE TABLE IF NOT EXISTS token_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(50) COMMENT '会话ID',
    agent_id BIGINT COMMENT 'Agent ID',
    provider_id BIGINT COMMENT '渠道ID',
    model_name VARCHAR(100) COMMENT '模型名称',
    prompt_tokens INT DEFAULT 0 COMMENT '输入Token数',
    completion_tokens INT DEFAULT 0 COMMENT '输出Token数',
    total_tokens INT DEFAULT 0 COMMENT '总Token数',
    cost DECIMAL(10,6) COMMENT '成本(预留)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_provider_id (provider_id),
    INDEX idx_model_name (model_name),
    INDEX idx_cost (cost),
    INDEX idx_created_at (created_at),
    INDEX idx_created_provider (created_at, provider_id),
    INDEX idx_created_agent (created_at, agent_id),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE SET NULL,
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE SET NULL,
    FOREIGN KEY (provider_id) REFERENCES llm_providers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token使用统计表';

-- ========================================
-- 11. 工具调用记录表
-- ========================================
CREATE TABLE IF NOT EXISTS tool_calls (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(50) NOT NULL COMMENT '会话ID',
    step_id VARCHAR(64) COMMENT '关联工作流步骤',
    agent_id BIGINT COMMENT 'Agent ID',
    tool_call_id VARCHAR(64) NOT NULL COMMENT '唯一标识',
    tool_name VARCHAR(200) NOT NULL COMMENT '工具名称',
    server_name VARCHAR(200) COMMENT 'MCP Server 名称（便于前端展示和调试）',
    tool_args JSON COMMENT '工具参数',
    tool_result LONGTEXT COMMENT '工具执行结果',
    status VARCHAR(20) DEFAULT 'RUNNING' COMMENT '状态: RUNNING/SUCCESS/FAILED',
    error_message TEXT COMMENT '错误信息',
    started_at TIMESTAMP NULL COMMENT '开始时间',
    completed_at TIMESTAMP NULL COMMENT '完成时间',
    duration_ms BIGINT COMMENT '执行耗时（毫秒）',
    sequence INT DEFAULT 0 COMMENT '同一步骤中的调用顺序',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_step_id (step_id),
    INDEX idx_tool_call_id (tool_call_id),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用记录表';

-- ========================================
-- 系统Agent初始化数据
-- ========================================
INSERT IGNORE INTO agents (provider_id, name, system_prompt, model, temperature, max_tokens, role_type, created_at, updated_at)
VALUES
(NULL, 'Planner Agent', '你是一个规划助手，负责将用户任务分解为可执行的步骤，并分配给合适的Worker执行。', 'gpt-4o', 0.7, 4096, 'PLANNER', NOW(), NOW());

-- ========================================
-- 迁移完成
-- ========================================

