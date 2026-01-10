-- 添加agent_id字段到token_usage表
ALTER TABLE token_usage
ADD COLUMN agent_id BIGINT COMMENT 'Agent ID' AFTER session_id;

-- 添加外键约束
ALTER TABLE token_usage
ADD CONSTRAINT fk_token_usage_agent
FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE SET NULL;

-- 添加索引（支持Agent效能查询）
CREATE INDEX idx_agent_id ON token_usage(agent_id);
CREATE INDEX idx_model_name ON token_usage(model_name);
CREATE INDEX idx_cost ON token_usage(cost);

-- 组合索引（优化时间范围查询）
CREATE INDEX idx_created_provider ON token_usage(created_at, provider_id);
CREATE INDEX idx_created_agent ON token_usage(created_at, agent_id);
