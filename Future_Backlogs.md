# ArcClaw Future Backlogs

Prioritized actionable items. Completed items are removed. Items are ordered by impact × urgency.

---

## Priority 2 — Observability & Operations

### 2.2 Consent / Data Provenance Tracking

Add lightweight provenance tags to RAG context entries — which source authorized which piece of data to flow to which agent.

- **Why**: Relevant when ArcClaw ingests external data sources (RAG, MCP file servers). Currently, session scoping partially handles this, but there's no explicit provenance tag on ingested chunks.
- **Effort**: Medium — add `source_session_id` and `ingested_by` fields to `rag_chunk` entity, propagate through `TwoTierMemoryService`.
- **When**: When MCP file server ingestion or multi-source RAG is implemented.

---

## Priority 3 — RAG & Module Hygiene

### 3.1 RAG Module Reorganization

Move `GlobalKnowledgeBase`, `SessionKnowledgeBase`, and `SqliteMemoryRetrievalAdapter` from `arcclaw-persistence-sqlite` to `arcclaw-rag` (or a dedicated `arcclaw-rag-sqlite` adapter).

- **Why**: These classes use LangChain4j types (`TextSegment`, `EmbeddingStore`) and architecturally belong in the RAG domain, not a general persistence adapter.
- **Effort**: Medium — module boundary move, dependency adjustments, test updates.
- **When**: Low priority — cosmetic, no behavioral change.

### 3.2 A16 Batch Decision Consolidation Across Adapters

Unify batch tool decision merging logic across all persistence adapters (SQLite, filesystem, Postgres).

- **Why**: Consolidated within SQLite into `CheckpointBatchOperations`, but `arcclaw-persistence-fs` has its own implementation path. Matters if a third adapter is added.
- **Effort**: Low-medium — extract shared utility, update fs adapter.
- **When**: Only if adding a new persistence adapter beyond the existing three.

---

## Completed (Removed from Backlog)

- Active agent inventory endpoint — `GET /sessions/active` returns live agent instances with status, role, depth, parent linkage, iterations, and pending tool
- Policy externalization via @ConfigMapping — `PolicyConfig` interface with `arcclaw.policy.*` config, refactored `DestructiveVocabularyFilter` and `ToolGovernanceGuard` to load from config
- Tool contract exporter — `ToolContractExporter` writes `data/tool-contracts.json` at startup with schema, governance metadata, secrets, and role grants
- Parent checkpoint linkage (`parent_checkpoint_id` + `depth` on `agent_checkpoint`, linked in `DelegateToAgentTool`)
- RRF score calibration (`min-score` default changed from `-1.0` to `0.01` — below minimum RRF score with k=60)
- AuditSchemaEnforcer cleanup (extracted to `FtsSchemaInitializer`, deleted enforcer)
- Flyway migration (quarkus-flyway, V1__baseline.sql, SessionSchemaEnforcer deleted, runonce renamed)
- Monolithic adapter split (SqliteCheckpointStoreAdapter: 900+ → 364 lines, 7 services)
- Cron test isolation (InstantToTextConverter moved to arcclaw-persistence-common)
