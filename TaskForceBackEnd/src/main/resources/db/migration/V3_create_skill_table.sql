-- ========================================
-- Skill 管理表
-- Version: 3.0
-- Date: 2026-02-07
-- ========================================

CREATE TABLE IF NOT EXISTS skills (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    skill_id VARCHAR(200) NOT NULL COMMENT 'Skill唯一标识（例如：skill_name）',
    name VARCHAR(200) NOT NULL COMMENT 'Skill名称',
    path VARCHAR(500) NOT NULL COMMENT 'Skill文件路径',
    enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_skill_id (skill_id),
    INDEX idx_enabled (enabled),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill管理表';
