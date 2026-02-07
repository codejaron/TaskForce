-- ========================================
-- 上下文系统：为 tool_calls 表添加 file_path 字段
-- Version: 2.10
-- Date: 2026-02-03
-- ========================================

ALTER TABLE tool_calls 
ADD COLUMN file_path VARCHAR(500) COMMENT '工具结果文件路径（相对于会话工作空间）' AFTER sequence;

-- 添加索引以便查询
CREATE INDEX idx_file_path ON tool_calls(file_path);
