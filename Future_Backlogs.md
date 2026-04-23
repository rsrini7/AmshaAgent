# ArcClaw Future Backlogs

## 1. Flyway Migration Plan (Long-term Schema Management)

Currently, some schema elements (Virtual Tables, WORM triggers, Session Registry) are managed via programmatic DDL. We should transition to `quarkus-flyway` for consistent, versioned migrations.

1.  **Add Dependencies**: Add `quarkus-flyway` to `arcclaw-persistence-sqlite` (and future adapter modules).
2.  **Initial Migration**: Create `src/main/resources/db/migration/V1__initial_schema.sql` capturing current entity tables.
3.  **WORM & FTS**: Migrate SQLite-specific WORM triggers and FTS5 virtual table creation to Flyway scripts.
4.  **Specific Migration Task**: 
    *   Rename `run_once` column to `runonce` in the `agent_cron` table to align with the new `@Column(name="runonce")` mapping.
    *   This ensures existing databases from the pre-P95 era can be upgraded without data loss.
5.  **Decommission Enforcers**: Delete `SessionSchemaEnforcer` and `AuditSchemaEnforcer` once Flyway is verified.
6.  **Architecture Invariant 5.23**: 
    *   "Schema migrations are managed by Flyway, scoped per persistence adapter module. No class may issue CREATE TABLE via raw JDBC or EntityManager at runtime."

## 2. Future Refactor Backlog (Architectural Debt)

These items were identified during the Tier 3/Tier 4 remediation phase and should be scheduled for a dedicated refactor sprint:

### 2.1 RAG Module Reorganization
Move `GlobalKnowledgeBase`, `SessionKnowledgeBase`, and `SqliteMemoryRetrievalAdapter` from `arcclaw-persistence-sqlite` to the `arcclaw-rag` module (or a dedicated `arcclaw-rag-sqlite` adapter). 
*   **Rationale**: These classes utilize LangChain4j types (`TextSegment`, `EmbeddingStore`) and architecturally belong in the RAG domain rather than a general persistence adapter.

### 2.2 Monolithic Adapter Split
Split `SqliteCheckpointStoreAdapter` (currently 900+ lines) into two focused components:
*   `SqliteCheckpointCrudAdapter`: Focused on basic entity/DTO mapping and basic persistence.
*   `SqliteCheckpointLifecycleAdapter`: Focused on complex orchestration like zombie recovery, stale checkpoint pruning, and atomic state transitions.

### 2.3 Cron Test Isolation
Resolve the `InstantToTextConverter` dependency hazard. 
*   **Rationale**: Currently, core unit tests for cron job retrieval in `arcclaw-core` rely on the converter registered in the `arcclaw-persistence-sqlite` module. Move the converter to `arcclaw-core` or establish a cleaner explicit dependency to prevent test isolation failures.

### 2.4 A16 Guard Consolidation
De-duplicate the batch tool decision merging logic (Architecture Nuance #16). 
*   **Rationale**: The logic for merging incoming decisions into the JSON map is currently duplicated between `StatefulAgentStoreImpl` and `SqliteCheckpointStoreAdapter`. It should be consolidated into a single atomic utility in the persistence layer.

### 2.5 RRF Score Calibration (RAG)
Calibrate `arcclaw.rag.min-score` for RRF fusion.
*   **Context**: The current default is `-1.0` to disable filtering because RRF scores (keyword + vector fusion) produce small positive values (e.g. 0.016) that were blocked by the original 0.55 threshold.
*   **Task**: Once the system has 50+ ingested documents, measure false-positive injection rates for unrelated queries.
*   **Goal**: Establish a sensible floor (e.g. `0.01`) to filter out noise while still allowing valid RRF matches to reach the agent.
