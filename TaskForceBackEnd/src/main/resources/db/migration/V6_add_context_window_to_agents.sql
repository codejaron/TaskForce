-- 为 agents 表增加上下文窗口字段
-- 用于按模型上下文容量计算摘要触发阈值
ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS context_window INT NULL COMMENT '上下文窗口(Token)，用于摘要触发阈值计算';
