# ArcClaw Future Backlogs

Prioritized actionable items. Completed items are removed. Items are ordered by impact × urgency.

---

## Priority 2 — Observability & Operations

### 2.2 Consent / Data Provenance Tracking

Add provenance tags to RAG chunks — userId, sessionId, consent status, and source attribution.

- **Why**: Zero provenance exists today. `RagChunkEntity.source` is hardcoded to `"System"`. `MemoryChunkDto` has no provenance fields. Without this, GDPR deletion requests and data lineage are impossible. Gated behind multi-source RAG — premature if external ingestion isn't imminent.
- **Effort**: Medium (3-5d) — schema changes (`rag_chunk` columns), DTO updates (`MemoryChunkDto`, `MemoryEntryDto`), service-layer filtering in `TwoTierMemoryService`, migration strategy for existing data.
- **When**: When MCP file server ingestion or multi-source RAG is implemented.

---

## Priority 3 — RAG & Module Hygiene

### 3.1 RAG Module Reorganization

Move `GlobalKnowledgeBase`, `SessionKnowledgeBase`, and `SqliteMemoryRetrievalAdapter` from `arcclaw-persistence-sqlite` to `arcclaw-rag` (or a dedicated `arcclaw-rag-sqlite` adapter).

- **Why**: 5 RAG classes (`GlobalKnowledgeBase`, `SessionKnowledgeBase`, `SqliteMemoryRetrievalAdapter`, `RagSchemaInitializer`, `RagChunkEntity`) live in `arcclaw-persistence-sqlite` instead of `arcclaw-rag`. The `arcclaw-rag` module exists but is thin — it only has the service facade and embedding providers. Moving them requires adding JPA/Hibernate dependency to `arcclaw-rag`, which increases its weight.
- **Effort**: Medium-High (5-8d) — move 5+ classes, add JPA dep, fix CDI bean discovery (jandex), move tests.
- **When**: Low priority — cosmetic, no behavioral change.

### 3.2 Batch Decision Unification

Unify batch decision method naming, transaction boundaries, and extract a shared `CheckpointBatchOperations` utility for all adapters.

- **Why**: SQLite, Postgres, and FS each have their own approach with divergent method names (`doAtomicUpdateBatchToolDecision` vs `atomicUpdateToolDecision` vs `atomicUpdateBatchToolDecision`) and transaction semantics (`REQUIRED` vs `REQUIRES_NEW` vs none). The stale-join guard (critical for preventing concurrent decision overwrites) was recently ported to Postgres and FS, but full unification remains.
- **Effort**: Medium — extract shared interface, align naming, standardize tx boundaries.
- **When**: Only if adding a new persistence adapter beyond the existing three.

---

## Completed (Removed from Backlog)

- Stale-join guard for batch decisions — `mergeBatchDecisions`-style preservation (A16) ported to Postgres and FS adapters to prevent concurrent batch-decision overwrites during approval turns
- Active agent inventory endpoint — `GET /sessions/active` returns live agent instances with status, role, depth, parent linkage, iterations, and pending tool
- Policy externalization via @ConfigMapping — `PolicyConfig` interface with `arcclaw.policy.*` config, refactored `DestructiveVocabularyFilter` and `ToolGovernanceGuard` to load from config
- Tool contract exporter — `ToolContractExporter` writes `data/tool-contracts.json` at startup with schema, governance metadata, secrets, and role grants
- Parent checkpoint linkage (`parent_checkpoint_id` + `depth` on `agent_checkpoint`, linked in `DelegateToAgentTool`)
- RRF score calibration (`min-score` default changed from `-1.0` to `0.01` — below minimum RRF score with k=60)
- AuditSchemaEnforcer cleanup (extracted to `FtsSchemaInitializer`, deleted enforcer)
- Flyway migration (quarkus-flyway, V1__baseline.sql, SessionSchemaEnforcer deleted, runonce renamed)
- Monolithic adapter split (SqliteCheckpointStoreAdapter: 900+ → 364 lines, 7 services)
- Cron test isolation (InstantToTextConverter moved to arcclaw-persistence-common)
