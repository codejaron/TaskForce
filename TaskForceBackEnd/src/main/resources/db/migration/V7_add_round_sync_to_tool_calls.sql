-- Tool call round/sync metadata for sandbox flush compensation
-- 1) round_id: bind tool calls to worker/chat round
-- 2) sync_status/sync_error/synced_at: eventual consistency tracking

ALTER TABLE tool_calls
    ADD COLUMN round_id VARCHAR(128) NULL COMMENT '轮次ID（Worker轮次/单聊一轮）' AFTER instance_id,
    ADD COLUMN sync_status VARCHAR(32) DEFAULT 'PENDING_SYNC' COMMENT '同步状态: SYNCED/PENDING_SYNC/SYNC_FAILED/SYNC_LOST_RISK' AFTER file_path,
    ADD COLUMN sync_error TEXT NULL COMMENT '同步错误' AFTER sync_status,
    ADD COLUMN synced_at TIMESTAMP NULL COMMENT '同步完成时间' AFTER sync_error;

CREATE INDEX idx_tool_calls_session_round ON tool_calls(session_id, round_id);
