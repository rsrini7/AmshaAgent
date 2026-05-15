# ArcClaw Future Backlogs

Prioritized actionable items. Completed items are removed. Items are ordered by impact × urgency.

---

## Priority 1 — Schema & Persistence (Do Before Sub-Agent Work)

### 1.1 Parent Checkpoint Linkage

Add `parent_checkpoint_id` and `depth` columns to the `agent_checkpoint` schema.

- **Why**: `DelegateToAgentTool` already spawns sub-agent loops, but checkpoints have no parent linkage. Building this after live data exists means a painful migration. The depth field enables future max-depth enforcement at the query level.
- **Effort**: Low — one Flyway migration + entity field + `AgentCheckpoint` record update.
- **When**: Before any work on multi-agent coordination or gRPC inter-agent communication.

### 1.2 RRF Score Calibration

Calibrate `arcclaw.rag.min-score` for RRF fusion. Current default is `-1.0` (disabled).

- **Why**: RRF scores (keyword + vector fusion) produce small positive values (e.g. 0.016) that were blocked by the original 0.55 threshold. The `-1.0` workaround disables filtering entirely.
- **Effort**: Low — measure false-positive injection rates with 50+ ingested documents, set a sensible floor (e.g. `0.01`).
- **When**: After accumulating 50+ ingested RAG documents in production.

### 1.3 AuditSchemaEnforcer Cleanup

Extract FTS5 setup from `AuditSchemaEnforcer` into a dedicated `FtsSchemaInitializer`, then delete the enforcer.

- **Why**: `AuditSchemaEnforcer` was gutted when WORM triggers moved to Flyway. It only manages FTS5 `CREATE VIRTUAL TABLE` now. The name is misleading.
- **Effort**: Low — rename + restructure, no behavioral change.
- **When**: Anytime.

---

## Priority 2 — Governance & Policy (Highest Architectural Leverage)

### 2.1 Policy Externalization via @ConfigMapping

Move tool governance rules from Java annotations and hardcoded guards to a loadable YAML/JSON policy config.

- **Why**: Current `DestructiveVocabularyFilter` and `ToolGovernanceGuard` are Java business logic — policy changes require code changes + redeploy. Externalized policies can be updated without touching application code.
- **Effort**: Medium — design YAML schema, implement `@ConfigMapping` policy loader, refactor `ToolGovernanceGuard` to read from config instead of annotations.
- **Format**: Each tool declares allowed roles, autonomy levels, data access scopes, and blocked patterns in a policy YAML loaded at startup.
- **When**: Next governance-focused sprint.

### 2.2 Tool Contract Exporter

Add a startup utility that serializes each registered tool's `@ToolMetadata` + `ToolCapability` entry to a versioned JSON file.

- **Why**: Tool capability scopes aren't queryable as standalone documents. Having inspectable, diffable tool contracts per deployment closes the "no formal contract artifact" gap and serves as auto-generated documentation.
- **Effort**: Low — one utility class that runs at `@Observes StartupEvent`, reads `ToolRegistry`, writes JSON.
- **When**: Anytime.

---

## Priority 3 — Observability & Operations

### 3.1 Active Agent Inventory Endpoint

Expose a `GET /api/v1/agents/active` endpoint showing live agent instances, their current `CheckpointStatus`, role, depth level, and trigger source.

- **Why**: ArcClaw can recover orphaned agents (`SessionReaper`), but cannot answer "how many agents are currently active, what are they doing, and who triggered them?" in real-time. This is a governance and operational visibility gap.
- **Effort**: Low — query `agent_checkpoint` for non-terminal statuses, expose via REST.
- **When**: Anytime.

### 3.2 Consent / Data Provenance Tracking

Add lightweight provenance tags to RAG context entries — which source authorized which piece of data to flow to which agent.

- **Why**: Relevant when ArcClaw ingests external data sources (RAG, MCP file servers). Currently, session scoping partially handles this, but there's no explicit provenance tag on ingested chunks.
- **Effort**: Medium — add `source_session_id` and `ingested_by` fields to `rag_chunk` entity, propagate through `TwoTierMemoryService`.
- **When**: When MCP file server ingestion or multi-source RAG is implemented.

---

## Priority 4 — RAG & Module Hygiene

### 4.1 RAG Module Reorganization

Move `GlobalKnowledgeBase`, `SessionKnowledgeBase`, and `SqliteMemoryRetrievalAdapter` from `arcclaw-persistence-sqlite` to `arcclaw-rag` (or a dedicated `arcclaw-rag-sqlite` adapter).

- **Why**: These classes use LangChain4j types (`TextSegment`, `EmbeddingStore`) and architecturally belong in the RAG domain, not a general persistence adapter.
- **Effort**: Medium — module boundary move, dependency adjustments, test updates.
- **When**: Low priority — cosmetic, no behavioral change.

### 4.2 A16 Batch Decision Consolidation Across Adapters

Unify batch tool decision merging logic across all persistence adapters (SQLite, filesystem, Postgres).

- **Why**: Consolidated within SQLite into `CheckpointBatchOperations`, but `arcclaw-persistence-fs` has its own implementation path. Matters if a third adapter is added.
- **Effort**: Low-medium — extract shared utility, update fs adapter.
- **When**: Only if adding a new persistence adapter beyond the existing three.

---

## Completed (Removed from Backlog)

- Flyway migration (quarkus-flyway, V1__baseline.sql, SessionSchemaEnforcer deleted, runonce renamed)
- Monolithic adapter split (SqliteCheckpointStoreAdapter: 900+ → 364 lines, 7 services)
- Cron test isolation (InstantToTextConverter moved to arcclaw-persistence-common)
