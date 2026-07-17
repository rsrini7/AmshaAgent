CREATE TABLE IF NOT EXISTS onboarding_commit_receipt (
    id          TEXT PRIMARY KEY,
    draft_id    TEXT NOT NULL,
    extension_id TEXT NOT NULL,
    operation_id TEXT NOT NULL UNIQUE,
    receipt     TEXT,
    state       TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_receipt_draft ON onboarding_commit_receipt(draft_id);
CREATE INDEX IF NOT EXISTS idx_receipt_state ON onboarding_commit_receipt(state);
