-- LLM profiles: connections, secret references, model profiles, route assignments, workspace grants
CREATE TABLE llm_connection (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT NOT NULL,
    created_by_user_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    provider_adapter_id TEXT NOT NULL,
    endpoint_profile_id TEXT,
    custom_base_url TEXT,
    auth_scheme TEXT,
    network_profile_json TEXT,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_llm_connection_scope ON llm_connection(scope_type, scope_id);
CREATE INDEX idx_llm_connection_created_by ON llm_connection(created_by_user_id);

CREATE TABLE llm_connection_secret_ref (
    connection_id TEXT NOT NULL,
    slot_id TEXT NOT NULL,
    vault_ref TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(connection_id, slot_id)
);
CREATE INDEX idx_llm_secret_ref_conn ON llm_connection_secret_ref(connection_id);

CREATE TABLE model_profile (
    id TEXT PRIMARY KEY,
    connection_id TEXT NOT NULL,
    model_id TEXT NOT NULL,
    structured_output_mode TEXT,
    capability_json TEXT,
    safe_limits_json TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_model_profile_conn ON model_profile(connection_id);

CREATE TABLE model_route_assignment (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT NOT NULL,
    target_kind TEXT NOT NULL,
    target_id TEXT,
    ordered_profile_ids TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_model_route_scope ON model_route_assignment(scope_type, scope_id);
CREATE INDEX idx_model_route_target ON model_route_assignment(target_kind, target_id);

CREATE TABLE workspace_connection_grant (
    workspace_id TEXT NOT NULL,
    connection_id TEXT NOT NULL,
    granted_by_user_id TEXT NOT NULL,
    grant_scope TEXT,
    state TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(workspace_id, connection_id)
);
CREATE INDEX idx_ws_grant_ws ON workspace_connection_grant(workspace_id);
CREATE INDEX idx_ws_grant_conn ON workspace_connection_grant(connection_id);
