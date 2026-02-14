-- Tool call persistence enhancement for Team mode history rebuild
-- 1) Track worker/lead source by instance_id
-- 2) Add compound indexes for timeline queries

ALTER TABLE tool_calls
    ADD COLUMN instance_id VARCHAR(128) NULL COMMENT '触发调用的实例ID（Worker/Lead）' AFTER server_name;

CREATE INDEX idx_tool_calls_session_started ON tool_calls(session_id, started_at);
CREATE INDEX idx_tool_calls_session_instance_started ON tool_calls(session_id, instance_id, started_at);
