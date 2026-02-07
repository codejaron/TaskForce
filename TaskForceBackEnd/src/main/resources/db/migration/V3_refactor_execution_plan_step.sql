-- ========================================
-- TaskForce - Execution Plan Step Refactoring
-- Version: 3.0
-- Date: 2026-02-07
-- Description: 拆分 execution_plan 表，将 steps_json 拆分为独立的 execution_plan_step 表
-- ========================================

-- ========================================
-- 1. 修改 execution_plan 表
-- ========================================

-- 移除 steps_json 列
ALTER TABLE execution_plan DROP COLUMN steps_json;

-- 新增 version 字段（乐观锁）
ALTER TABLE execution_plan ADD COLUMN version INT DEFAULT 0 COMMENT '版本号（乐观锁）' AFTER replan_count;

-- 新增 current_layer_index 字段
ALTER TABLE execution_plan ADD COLUMN current_layer_index INT DEFAULT 0 COMMENT '当前执行层级索引（用于并行执行）' AFTER current_step_index;

-- ========================================
-- 2. 创建 execution_plan_step 表
-- ========================================

CREATE TABLE IF NOT EXISTS execution_plan_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    plan_id VARCHAR(64) NOT NULL COMMENT '计划ID（关联 execution_plan.plan_id）',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID（冗余字段，便于查询）',
    step_id VARCHAR(64) NOT NULL COMMENT '步骤唯一标识（UUID）',
    step_index INT NOT NULL COMMENT '步骤索引（全局顺序）',
    layer_index INT DEFAULT 0 COMMENT '层级索引（用于并行执行，同层可并行）',
    assigned_agent_id BIGINT COMMENT '分配的 Agent ID',
    assigned_agent_name VARCHAR(255) COMMENT '分配的 Agent 名称（冗余字段）',
    instruction TEXT NOT NULL COMMENT '步骤指令',
    expected_output TEXT COMMENT '期望输出',
    depends_on JSON COMMENT '依赖的步骤ID列表（JSON数组）',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED/BLOCKED',
    blocked_reason TEXT COMMENT '阻塞原因',
    output_summary TEXT COMMENT '输出摘要',
    version INT DEFAULT 0 COMMENT '版本号（乐观锁）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引
    INDEX idx_plan_id (plan_id),
    INDEX idx_session_id (session_id),
    INDEX idx_step_id (step_id),
    INDEX idx_status (status),
    INDEX idx_step_index (step_index),
    INDEX idx_layer_index (layer_index),
    INDEX idx_assigned_agent_id (assigned_agent_id),
    UNIQUE KEY uk_plan_step (plan_id, step_id),

    -- 外键约束
    FOREIGN KEY (plan_id) REFERENCES execution_plan(plan_id) ON DELETE CASCADE,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE,
    FOREIGN KEY (assigned_agent_id) REFERENCES agents(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='执行计划步骤表';

-- ========================================
-- 迁移完成
-- ========================================
