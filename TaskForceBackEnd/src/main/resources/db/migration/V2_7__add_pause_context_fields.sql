-- 添加pause上下文字段到execution_plan表
ALTER TABLE execution_plan
ADD COLUMN paused_by VARCHAR(32) COMMENT '暂停触发源: PLANNER/WORKER/USER/BLOCKED' AFTER pause_reason,
ADD COLUMN paused_at_step_index INT COMMENT 'Worker澄清时记录的步骤索引' AFTER paused_by,
ADD COLUMN paused_agent_id VARCHAR(64) COMMENT 'Worker澄清时记录的Agent ID' AFTER paused_at_step_index;

-- 添加索引（支持暂停来源查询）
CREATE INDEX idx_paused_by ON execution_plan(paused_by);
