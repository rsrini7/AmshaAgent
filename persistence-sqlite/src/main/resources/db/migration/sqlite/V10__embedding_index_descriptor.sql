-- Full embedding-index compatibility metadata. Existing development vectors are not migrated;
-- non-empty namespaces without matching metadata degrade to text search until explicitly reset.
CREATE TABLE vector_index_metadata (
    namespace                 TEXT PRIMARY KEY,
    schema_version            INTEGER NOT NULL CHECK (schema_version > 0),
    provider_id               TEXT NOT NULL,
    space_id                  TEXT NOT NULL,
    model_revision            TEXT NOT NULL,
    tokenizer_revision        TEXT NOT NULL,
    preprocessing_revision    TEXT NOT NULL,
    pooling_strategy          TEXT NOT NULL,
    l2_normalize              INTEGER NOT NULL CHECK (l2_normalize IN (0, 1)),
    vector_dimension          INTEGER NOT NULL CHECK (vector_dimension > 0),
    numeric_precision         TEXT NOT NULL,
    chunking_strategy         TEXT NOT NULL,
    chunking_revision         TEXT NOT NULL,
    max_window_tokens         INTEGER NOT NULL CHECK (max_window_tokens >= 0),
    window_overlap_tokens     INTEGER NOT NULL CHECK (window_overlap_tokens >= 0),
    multi_window_aggregation  TEXT NOT NULL,
    descriptor_fingerprint    TEXT NOT NULL CHECK (length(descriptor_fingerprint) = 64),
    created_at                TEXT NOT NULL,
    updated_at                TEXT NOT NULL
);
