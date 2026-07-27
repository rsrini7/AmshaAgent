-- Onboarding drafts, extension state, and commit receipts

-- Onboarding draft + extension state (durable, resumable, non-secret)
CREATE TABLE onboarding_draft (
    draft_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    purpose TEXT NOT NULL,
    plan_fingerprint TEXT,
    current_step_id TEXT,
    completed_step_ids TEXT,
    selected_non_secret_ids TEXT,
    safe_field_errors TEXT,
    pending_approval_ids TEXT,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_onboarding_draft_owner ON onboarding_draft(owner_id);

CREATE TABLE onboarding_extension_state (
    draft_id TEXT NOT NULL,
    extension_id TEXT NOT NULL,
    extension_version TEXT,
    scope TEXT,
    outcome TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(draft_id, extension_id)
);
CREATE INDEX idx_onboarding_ext_draft ON onboarding_extension_state(draft_id);

CREATE TABLE IF NOT EXISTS onboarding_commit_receipt (
    id           TEXT PRIMARY KEY,
    draft_id     TEXT NOT NULL,
    extension_id TEXT NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    receipt      TEXT,
    state        TEXT NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_receipt_draft ON onboarding_commit_receipt(draft_id);
CREATE INDEX IF NOT EXISTS idx_receipt_state ON onboarding_commit_receipt(state);
