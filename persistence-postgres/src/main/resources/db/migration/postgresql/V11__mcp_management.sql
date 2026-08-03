ALTER TABLE mcp_connection ADD COLUMN state TEXT NOT NULL DEFAULT 'DISCONNECTED';
ALTER TABLE mcp_connection ADD COLUMN approval_task_id TEXT;
ALTER TABLE mcp_connection ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE mcp_connection ADD COLUMN last_error_code TEXT;

UPDATE mcp_connection SET state = CASE WHEN enabled THEN 'DISCONNECTED' ELSE 'PAUSED' END;

CREATE TABLE mcp_connection_tool_policy (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL REFERENCES mcp_connection(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    description TEXT NOT NULL DEFAULT '',
    input_schema TEXT NOT NULL DEFAULT '{}',
    schema_hash TEXT NOT NULL DEFAULT '',
    discovered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(connection_id, user_id, tool_name)
);

CREATE INDEX idx_mcp_tool_policy_connection
    ON mcp_connection_tool_policy(connection_id, user_id);
