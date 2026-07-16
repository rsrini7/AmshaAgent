-- Identity user
CREATE TABLE identity_user (
    user_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- External identity linking
CREATE TABLE external_identity (
    provider_id TEXT NOT NULL,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES identity_user(user_id) ON DELETE CASCADE,
    safe_claims TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(provider_id, issuer, subject)
);
CREATE INDEX idx_external_identity_user ON external_identity(user_id);

-- Workspace
CREATE TABLE identity_workspace (
    workspace_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    created_by TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Workspace membership
CREATE TABLE workspace_membership (
    workspace_id TEXT NOT NULL REFERENCES identity_workspace(workspace_id) ON DELETE CASCADE,
    user_id TEXT NOT NULL REFERENCES identity_user(user_id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    status TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(workspace_id, user_id)
);
CREATE INDEX idx_workspace_membership_user ON workspace_membership(user_id);

-- API credential
CREATE TABLE api_credential (
    credential_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES identity_user(user_id) ON DELETE CASCADE,
    key_digest TEXT NOT NULL UNIQUE,
    role_set TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_api_credential_user ON api_credential(user_id);

-- Web session
CREATE TABLE web_session (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES identity_user(user_id) ON DELETE CASCADE,
    token_digest TEXT NOT NULL UNIQUE,
    csrf_digest TEXT NOT NULL,
    idle_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    absolute_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    rotated_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    safe_client_metadata TEXT,
    revision INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_web_session_user ON web_session(user_id);
CREATE INDEX idx_web_session_token ON web_session(token_digest);

-- Identity invitation
CREATE TABLE identity_invitation (
    invitation_id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES identity_workspace(workspace_id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    token_digest TEXT NOT NULL UNIQUE,
    optional_email_constraint TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_by TEXT,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revision INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_identity_invitation_workspace ON identity_invitation(workspace_id);

-- Bootstrap state (durable revisioned state machine)
CREATE TABLE bootstrap_state (
    id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    owner_id TEXT,
    credential_digest TEXT,
    issued_at TIMESTAMP WITH TIME ZONE,
    claimed_at TIMESTAMP WITH TIME ZONE,
    revision INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
