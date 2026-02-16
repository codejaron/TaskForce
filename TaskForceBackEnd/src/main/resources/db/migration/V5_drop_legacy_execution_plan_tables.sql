-- ========================================
-- TaskForce - Drop Legacy Planner/Worker Tables
-- Version: 5.0
-- Date: 2026-02-16
-- Description: 移除旧 planner-worker 编排专用表
-- ========================================

DROP TABLE IF EXISTS execution_plan_step;
DROP TABLE IF EXISTS execution_plan;
