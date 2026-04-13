## WAVE 3 READINESS – Next Steps
> **All prerequisites satisfied. Wave 3 can begin immediately.**

### Recommended Wave 3 Execution Order
1. **Create arcclaw-persistence-api module (C1 Phase 1)** — port interfaces only (tiny PR)
2. **Create arcclaw-persistence-sqlite module (C1 Phase 2)** — move 7 files from core.persistence
3. **Implement SqliteCheckpointStoreAdapter (C1 Phase 2)** — extract queries from StatefulAgentStoreImpl
4. **Implement SqliteMemoryRetrievalAdapter (C1 Phase 2)** — extract from TwoTierMemoryService
5. **Rewire StatefulAgentStoreImpl -> ports (C1 Phase 3)** — biggest single change
6. **Rewire WorkspaceManager + SessionReaper (C1 Phase 3)** — delegate to CheckpointStorePort (retry is adapter-internal)
7. **Rewire RAG module -> MemoryRetrievalPort (C1 Phase 3)** — arcclaw-rag depends on persistence-api
8. **Delete core.persistence package (C1 Phase 3)** — verify empty
9. **Add contract tests (C1 Phase 4)** — InMemoryCheckpointStoreAdapter
10. **Begin C2 god-class decomposition in parallel** — StatefulAgentStoreImpl first

---

## CRITICAL Fixes (Do First)
### C1) Storage backend is not truly swappable
**Goal**: Isolate SQLite behavior behind ports/adapters so a Postgres (or any other) backend is a **new sibling module**, never code inside `arcclaw-core`.

#### Where the SQLite coupling actually lives today
| Layer | Module | Files | What leaks |
| :--- | :--- | :--- | :--- |
| **Persistence infra** | `arcclaw-core` -> `core.persistence` | `SqliteRetryTemplate`, `ArcClawSQLiteDialect`, `FtsConnectionInterceptor`, `SqliteErrorStrings`, `PersistenceRetryEngine` | SQLite retry semantics, dialect hacks, FTS5 connection bootstrap |
| **State / checkpoint** | `arcclaw-core` -> `core.state` | `StatefulAgentStoreImpl` (injects `SqliteRetryTemplate`), `WorkspaceManager` (injects `SqliteRetryTemplate`), `SessionReaper` (injects `SqliteRetryTemplate`) | `REQUIRES_NEW` patterns, `BUSY` retry wrapping, SQLite timestamp formatting |
| **Approval / HITL** | `arcclaw-core` -> `core.approval` | `CheckpointApprovalService` (comments reference "SQLite write lock", "XA BEGIN IMMEDIATE") | SQLite-specific transaction ordering knowledge baked into comments and control flow |
| **RAG / memory** | `arcclaw-rag` | `TwoTierMemoryService`, `GlobalKnowledgeBase`, `RagSchemaInitializer` (all inject `FtsConnectionInterceptor` from `core.persistence`) | FTS5 + `sqlite-vec` assumptions, raw SQL for FTS5 MATCH |
| **App bootstrap** | `arcclaw-app` | `SqliteWalConfigurer`, `ChatStreamResource` | WAL mode enforcement, startup schema setup |

**Key insight**: The SQLite coupling is **primarily inside `arcclaw-core` itself** (`core.persistence` + `core.state`), not just between core and rag. The rag module's coupling is secondary — it imports `FtsConnectionInterceptor` and uses `CompactionSummaryRepository` from core.

---

#### Target module structure after refactoring
```text
arcclaw-persistence-api/          <- NEW module (tiny: interfaces only)
    CheckpointStorePort.java
    MemoryRetrievalPort.java
    BackendCapabilities.java      (FTS? vector? advisory locks?)
    NOTE: PersistenceRetryPort removed - retry is adapter-internal (5.14.1)

arcclaw-core/                     <- EXISTING (domain + orchestration)
    core.state/
        AgentCheckpoint.java      (entity - stays here)
        CheckpointStatus.java     (enum - stays here)
        StatefulAgentStore.java   (interface - stays here)
        StatefulAgentStoreImpl.java (injects CheckpointStorePort, NOT SqliteRetryTemplate)
    core.persistence/
        * EMPTY after extraction * (all SQLite files move out)
    ... (rest of domain/orchestration unchanged)

arcclaw-persistence-sqlite/       <- NEW module (current SQLite infra moves here)
    SqliteCheckpointStoreAdapter.java (implements CheckpointStorePort)
    SqliteRetryTemplate.java          (moved from core.persistence)
    ArcClawSQLiteDialect.java         (moved from core.persistence)
    FtsConnectionInterceptor.java     (moved from core.persistence)
    SqliteErrorStrings.java           (moved from core.persistence)
    SqliteWalConfigurer.java          (moved from arcclaw-app)
    SqliteMemoryRetrievalAdapter.java (implements MemoryRetrievalPort, wraps FTS5+sqlite-vec)

arcclaw-persistence-postgres/     <- FUTURE module (sibling, NOT inside core)
    PostgresCheckpointStoreAdapter.java (implements CheckpointStorePort)
    PostgresMemoryRetrievalAdapter.java (implements MemoryRetrievalPort, uses pgvector+tsvector)
    Note: No retry adapter needed - Postgres uses MVCC, retry is adapter-internal if needed at all
    
```

**Answer to your question**: No — Postgres code should **never** go inside `arcclaw-core`. The port interfaces live in a thin `arcclaw-persistence-api` module. Each backend gets its own module (`arcclaw-persistence-sqlite`, `arcclaw-persistence-postgres`). Core depends only on the API module. At runtime, CDI picks the active adapter based on which module is on the classpath / active Quarkus profile.

---

#### Detailed refactoring steps

**Phase 1: Extract port interfaces (`arcclaw-persistence-api`)**
1. Create `arcclaw-persistence-api` module with zero dependencies except Jakarta CDI API.
2. Define port interfaces:
```java
public interface CheckpointStorePort {
    AgentCheckpoint save(AgentCheckpoint checkpoint);
    Optional<AgentCheckpoint> findById(Long id);
    List<AgentCheckpoint> findActiveBySession(String sessionId);
    void transitionStatus(Long id, CheckpointStatus newStatus);
    List<AgentCheckpoint> findStaleApprovals(Instant cutoff);
}

public interface MemoryRetrievalPort {
    List<ScoredChunk> search(String query, int topK, String sessionId);
    void index(MemoryEntry entry);
    BackendCapabilities capabilities();
}

// NOTE: PersistenceRetryPort intentionally ommitted.
// Retry-on-BUSY is a SQLite-specific concern encapsulated inside
// SqliteCheckpointStoreAdapter. See Architecture_Invariants (5.14.1)

public record BackendCapabilities(
    boolean supportsFullTextSearch,
    boolean supportsVectorSearch,
    boolean supportsAdvisoryLocks,
    boolean supportsNestedTransactions
) {}
```
3. Add `arcclaw-persistence-api` as dependency of `arcclaw-core` (compile scope).

**Phase 2: Create `arcclaw-persistence-sqlite` module**
1. Move these files from `arcclaw-core/core.persistence/`:
    * `SqliteRetryTemplate.java` -> stays SQLite-internal (adapter uses it directly, NOT exposed as port)
    * `ArcClawSQLiteDialect.java` -> stays SQLite-specific
    * `FtsConnectionInterceptor.java` -> stays SQLite-specific
    * `SqliteErrorStrings.java` -> stays SQLite-specific
    * `PersistenceRetryEngine.java` -> merges into SQLite adapter
2. Create `SqliteCheckpointStoreAdapter` implements `CheckpointStorePort`:
    * Wraps `EntityManager` + `SqliteRetryTemplate` + `REQUIRES_NEW` boundaries
    * Contains all JPQL queries currently in `StatefulAgentStoreImpl`
    - **Encapsulates retry loops internally** - callers see only `CheckpointStorePort` methods
3. Create `SqliteMemoryRetrievalAdapter` implements `MemoryRetrievalPort`:
    * Wraps `FtsConnectionInterceptor` + FTS5 MATCH queries + sqlite-vec
    * Contains logic currently in `TwoTierMemoryService`
4. Move `SqliteWalConfigurer` from `arcclaw-app`.
5. Module depends on: `arcclaw-persistence-api`, Hibernate, SQLite driver.

---

**Phase 3: Rewire core to use ports**
1. `StatefulAgentStoreImpl`:
    * Remove `@Inject SqliteRetryTemplate` -> `@Inject PersistenceRetryPort`
    * Remove `withRetry()` wrapper method - no longer needed
    * Remove direct JPQL queries -> retry is now inside `SQliteCheckpointStoreAdapter`
    * Keep status transition logic, audit emission, resume lock (these are domain, not storage)
2. `WorkspaceManager`:
    * Remove `@Inject SqliteRetryTemplate` -> delegate to `CheckpointStorePort.markFailed(sessionId)`
    * The adapter handles retry internally
3. `SessionReaper`:
    * Remove `@Inject SqliteRetryTemplate` -> delegate to `CheckpointStorePort` methods
    * The adapter handles retry internally
4. `TwoTierMemoryService`:
    * Remove `@Inject FtsConnectionInterceptor` -> `@Inject MemoryRetrievalPort`
5. `arcclaw-core` `pom.xml`: remove SQLite driver dependency, keep only `arcclaw-persistence-api`.

**Phase 4: Profile-based activation**
```yaml
# application.yaml
arcclaw:
  persistence:
    backend: ${ARCCLAW_PERSISTENCE_BACKEND:sqlite} # default = sqlite

# Create application-postgres.yaml (Quarkus profile) under arcclaw-app
# Activated via: -Dquarkus.profile=postgres (composable: -Dquarkus.profile=prod,postgres)
quarkus:
  datasource:
    db-kind: postgresql
    jdbc:
      url: "{ARCCLAW_PG_URL:jdbc:postgresql://localhost:5432/arcclaw}"
      username: "${ARCCLAW_PG_USER:arcclaw}"
      password: "${ARCCLAW_PG_PASSWORD:}"
      min-size: 2
      max-size: 10
  hibernate-orm:
    dialect:
    schema-management:
      strategy: update
    
```
CDI selects the active adapter via `@IfBuildProfile("sqlite")` / `@IfBuildProfile("postgres")` or `@LookupIfProperty`.

**Phase 5 (future): Create `arcclaw-persistence-postgres`**
1. `PostgresCheckpointStoreAdapter` implements `CheckpointStorePort`:
    * Standard JPA/JPQL (no BUSY retry needed)
    * `@Transactional` boundaries without SQLite-specific `REQUIRES_NEW` workarounds
    * May use advisory locks internally for distributed coordination if needed
2. `PostgresMemoryRetrievalAdapter` implements `MemoryRetrievalPort`:
    * `tsvector` for full-text search (replaces FTS5)
    * `pgvector` for vector search (replaces sqlite-vec)
    * No `FtsConnectionInterceptor` needed
    Note: No PersistenceRetryPort needed - Postgres uses MVCC. Any retry logic
    (e.g., optimistic lock retries) is adapter-internal, not exposed to core.

---

#### Why this structure is correct

| Concern | Wrong approach | Right approach |
| :--- | :--- | :--- |
| Postgres adapter location | Inside `arcclaw-core` | Separate `arcclaw-persistence-postgres` module |
| Port interfaces location | Inside `arcclaw-core` | Separate `arcclaw-persistence-api` module (core depends on it) |
| SQLite code after refactoring | Stays in `arcclaw-core` | Moves to `arcclaw-persistence-sqlite` |
| Backend selection | Compile-time `if/else` in core | CDI profile-based adapter injection |
| RAG module coupling | Imports `FtsConnectionInterceptor` from core | Imports `MemoryRetrievalPort` from persistence-api |

**The refactoring touches 3 modules initially** (core, rag, new persistence-api) and **creates 1 new module** (persistence-sqlite). It is NOT just a core$\leftrightarrow$rag shuffle. The key move is extracting the entire `core.persistence` package OUT of core into its own module.

---

**Invariant Linkage**:
* Fulfills `Architecture_Invariants.md` §5 persistence abstraction intent
* Unblocks `ARCCLAW_EXTENSION.md` storage backend extensibility promise

**Test Strategy**:
* Create `InMemoryCheckpointStoreAdapter` for fast unit tests
* Port existing `StatefulAgentStoreImplTest` to run against both in-memory and SQLite adapters
* Add contract test suite that validates any `CheckpointStorePort` implementation
* `arcclaw-persistence-postgres` gets its own Testcontainers-based integration tests

**Done When**
* `arcclaw-core` compiles with zero SQLite imports
* `arcclaw-rag` compiles with zero `FtsConnectionInterceptor` imports
* Adding `arcclaw-persistence-postgres` to classpath + activating profile switches backend without touching core/rag code
* All 1095 existing tests still pass against SQLite adapter

---

#### Deep-Dive: Class-by-Class Impact & Side-Effects

##### 1. `StatefulAgentStoreImpl.java` (744 lines) – THE BIGGEST IMPACT

**Is it "mostly SQLite syntax"?** – Not exactly. It's a mix:

| Responsibility | SQLite-specific? | Stays in core? | Moves to adapter? |
| :--- | :--- | :--- | :--- |
| `em.createQuery(JPQL)` – 12 queries | **No** – standard JPA/JPQL; works on any DB | [ ] stays | [x] moves |
| `@Transactional(REQUIRES_NEW)` – 10 occurrences | **Partially** – forced by SQLite single-writer; Postgres wouldn't need most of them | [ ] stays but annotations change | [x] moves to adapter |
| `em.clear()` before every query – 8 occurrences | **Yes** – SQLite workaround to avoid stale L1 cache after BUSY retry | [ ] stays | [x] moves to adapter |
| `.withRetry(() -> self.doXxx(), "op")` – 7 occurrences | **Yes** – wraps every write in `SqliteRetryTemplate.execute()` | [ ] stays | [x] moves to adapter |
| `self.doXxx()` self-injection CDI pattern | **Yes** – enables `@Transactional(REQUIRES_NEW)` on same-class calls, forced by SQLite lock avoidance | [ ] stays | [x] moves to adapter |
| `resumingBatches` ConcurrentHashMap (resume lock) | **No** – in-memory JVM coordination, database-agnostic | [x] stays | [ ] |
| `mergeBatchDecisions()` JSON merge logic | **No** – domain logic for HITL batch decisions | [x] stays | [ ] |
| `serializeSteps()` / `deserializeSteps()` Jackson JSON | **No** – serialization, database-agnostic | [x] stays | [ ] |
| `auditService.record()` calls inside save/fail/complete | **No** – domain audit, but **side-effect risk** (see below) | [x] stays | [ ] |
| `LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC)` in `findStaleAwaitingApproval` | **Yes** – SQLite stores timestamps as TEXT, needs explicit formatting; Postgres uses `timestamptz` natively | [ ] stays | [x] moves to adapter |
| `findBySessionAndRole()` – ORDER BY c.id DESC | **Partially** – the fix comment says "Previously, LIMIT 1 with no ordering caused SQLite to return the oldest row"; Postgres would also benefit from ORDER BY but wouldn't have the original bug | [x] stays (JPQL) | [ ] |

**Side-effect: Audit duplication on retry (M9)**
`doSaveSteps()` calls `auditService.record(CHECKPOINT_CREATED)` **inside** the retried block. On SQLite BUSY retry, this creates duplicate audit events. When extracting to adapter, you must decide:
* Option A: Move audit call outside retry boundary (audit once after success).
* Option B: Make audit idempotent via dedup key.
If you just move the code as-is, the bug travels to the adapter unchanged.

**Side-effect: The `self` injection pattern breaks if class is split naively**
`StatefulAgentStoreImpl` injects `@Inject StatefulAgentStoreImpl self` to get CDI proxy for `@Transactional(REQUIRES_NEW)` on same-class methods. This is a SQLite-specific pattern – Postgres wouldn't need `REQUIRES_NEW` on most operations. If the adapter keeps this pattern, it's waste. If removed for Postgres, the two adapters diverge in transaction architecture.

**Recommended split**:
* `StatefulAgentStoreImpl` (stays in core, ~200 lines after extraction)
    * Delegates persistence to `CheckpointStorePort`
    * Keeps: resume lock, batch decision merge, serialization, audit
    * Removes: `em.*`, `@Transactional`, `retryTemplate`, self-injection
* `SqliteCheckpointStoreAdapter` (new, in arcclaw-persistence-sqlite, ~350 lines)
    * All `em.createQuery()` calls
    * `@Transactional(REQUIRES_NEW)` + `em.clear()` patterns
    * `SqliteRetryTemplate` wrapping
    * `LocalDateTime` timestamp formatting
    * self-injection for CDI proxy

---

##### 2. `WorkspaceManager.java` (241 lines) – MODERATE IMPACT

**SQLite-coupled lines**:
```java
@Inject SqliteRetryTemplate retryTemplate; // line 51
@Transactional(Transactional.TxType.REQUIRES_NEW) // line 167
// "transaction (REQUIRES_NEW) hasn't fully flushed to SQLite before this query runs" // line 159
```

**What moves**: Only the retry wrapping. The workspace cleanup logic itself (delete files, mark checkpoints failed) is domain behavior that stays in core.

**Side-effect: `doMarkCheckpointsFailedRequiresNew()` has the status list mismatch (XB2)**
This method excludes only 4 terminal statuses (FAILED, COMPLETED, ARCHIVED, COMPROMISED) while `isSessionActive()` in `StatefulAgentStoreImpl` excludes 6. When you move query logic to adapter, you must centralize terminal status definition FIRST (ED2/XB2 fix) or the bug propagates into adapter.

**Side-effect: Path safety (H9)**
`wipeWorkspaceDir()` uses raw `sessionId` without `toSafeDirName()`. This is NOT related to SQLite extraction, but will be touched during the same refactoring. Fix H9 first or at the same time.

---

##### 3. `SessionReaper.java` (101 lines) – MODERATE IMPACT

**SQLite-coupled lines**:
```java
@Inject SqliteRetryTemplate retryTemplate; // line 63
// "This prevents SQLITE_BUSY / cannot commit - no transaction is active" // line 46-47
// "Without this, SQLite compares TEXT representations of Instant vs LocalDateTime incorrectly" // line 101
```

**The `Instant` -> `LocalDateTime` formatting is a hidden portability bomb**:
```java
// SessionReaper.java line 101 (implied)
// SQLite stores Instant as TEXT; queries must use LocalDateTime for comparison
// Postgres uses timestamptz natively – no conversion needed
```

**Side-effect**: If `SessionReaper` is left injecting `SqliteRetryTemplate` directly while `StatefulAgentStoreImpl` is refactored to use `CheckpointStorePort`, you have an inconsistency. The reaper's cleanup queries should also go through the port, or at minimum through the same adapter.

---

##### 4. `CheckpointApprovalService.java` – LOW DIRECT IMPACT BUT HIGH COMMENT COUPLING

**No direct `SqliteRetryTemplate` import.** But the code and comments are deeply SQLite-aware:
```java
// line 30: "doUpdateBatchToolDecision is @Transactional(REQUIRES_NEW) and manages"
// line 32: "SQLite write lock (XA BEGIN IMMEDIATE) that blocks the inner COMMIT"
// line 35: "without an outer tx - SQLite auto-commits SELECT statements"
// line 118: "ensures the SQLite write lock is fully released"
```

**Side-effect**: The entire transaction choreography in this class is designed around SQLite's single-writer constraint. On Postgres, this elaborate dance of `NOT_SUPPORTED` -> `REQUIRES_NEW` -> `SUPPORTS` is unnecessary. The Postgres adapter can use a simple `@Transactional` with row-level locks. But the core orchestration class will still have these annotations – they become dead weight on Postgres.

**Fix**: The approval transaction choreography should be backend-aware. Either:
* Move the transaction boundary decisions into the adapter layer.
* Or make `@Transactional` annotations configurable per profile (harder in CDI).

---

##### 5. `FtsConnectionInterceptor.java` (147 lines) – MOVES ENTIRELY

**100% SQLite-specific.** Does:
* ATTACH FTS database on every connection acquisition
* Load sqlite-vec extension per-connection
* Check `pragma_module_list` for already-loaded modules
* Unwrap `SQLiteConnection` directly

**Side-effect on RAG module**: `TwoTierMemoryService`, `GlobalKnowledgeBase`, `RagSchemaInitializer` all `@Inject FtsConnectionInterceptor`. After move:
* These must inject `MemoryRetrievalPort` instead.
* The `isVectorSearchEnabled()` check (currently direct method call) becomes `port.capabilities().supportsVectorSearch()`.
* `RagSchemaInitializer` startup logic (FTS table creation) becomes adapter-internal.

---

##### 6. `PersistenceRetryEngine.java` (273 lines) – MOVES ENTIRELY

**100% SQLite retry/async logic.** Wraps operations with BUSY detection and retry. On Postgres, this is replaced by connection pool retry + advisory locks.

---

##### 7. `AsyncPersistenceEngine.java` (399 lines) – PARTIALLY MOVES

This is the background audit/telemetry drain. It uses virtual threads and has BUSY handling. The async mechanism itself is backend-agnostic, but the error handling is SQLite-specific. Split:
* Async framework stays in core.
* Error classification (`SqliteErrorStrings.isBusy()`) moves to adapter.

---

##### 8. All 5 Repository Classes – STAY BUT NEED REVIEW

| Repository | Lines | SQLite-specific? | Impact |
| :--- | :--- | :--- | :--- |
| `AgentCronRepository` | 280 | `REQUIRES_NEW` patterns, JPQL is standard | Transaction annotations may need profile-based config |
| `CompactionSummaryRepository` | 156 | Used by RAG; JPQL is standard | No change if port handles memory retrieval |
| `AuditEventRepository` | 119 | WORM triggers are SQLite-specific (see `AuditSchemaEnforcer`) | Trigger setup must be adapter-specific |
| `AgentSkillRepository` | 55 | Standard JPQL | No change |
| `EncryptedSecretRepository` | 39 | Standard JPQL | No change |

**Side-effect**: `AuditSchemaEnforcer` enforces WORM compliance using **SQLite triggers**. On Postgres, this must be reimplemented as Postgres triggers or row-level security policies. If you miss this, audit data on Postgres is no longer WORM-protected.

---

##### 9. `ArcClawSQLiteDialect.java` (34 lines) – MOVES ENTIRELY

Fixes Hibernate ORM 7 DDL regression for SQLite `INTEGER PRIMARY KEY`. Pure SQLite workaround. Postgres doesn't need this.

---

##### 10. Hidden Side-Effects Summary

| Side-Effect | Severity | When it bites |
| :--- | :--- | :--- |
| **Audit duplication on retry (M9)** | **HIGH** | Travels to adapter if not fixed first; Every SQLite BUSY retry creates duplicate CHECKPOINT_CREATED events |
| **Terminal status list mismatch (XB2/ED2)** | **HIGH** | hardcoded in multiple places; Adding new terminal status breaks cleanup queries in both adapter AND core |
| **Transaction annotation divergence** | **MEDIUM** | `REQUIRES_NEW` needed for SQLite but wasteful for Postgres; Postgres adapter pays transaction overhead for no reason if annotations kept |
| **Timestamp format coupling** | **MEDIUM** | `LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC)`; Postgres adapter must NOT do this conversion; silent wrong results if copy-pasted |
| **WORM audit triggers** | **MEDIUM** | are SQLite-specific; Postgres has no audit integrity protection if triggers not reimplemented |
| **"self" CDI injection** | **LOW** | pattern for same-class `@Transactional`; Confusing for Postgres adapter developers; temptation to copy pattern unnecessarily |
| **`em.clear()` before every query** | **LOW** | Unnecessary on Postgres; leftover calls are harmless but misleading |
| **`isVectorSearchEnabled()` method coupling** | **LOW** | RAG module calls this directly; must become capability check on port |

---

##### Recommended Execution Order for C1

1. Fix ED2/XB2 first – centralize terminal statuses before touching queries
2. Fix M9 first – move audit outside retry before extracting retry to adapter
3. Fix H9 – path safety before touching WorkspaceManager
4. Create persistence-api – port interfaces only (tiny PR)
5. Create persistence-sqlite – move files, implement adapters (biggest PR)
6. Rewire StatefulAgentStoreImpl – inject ports, remove SQLite imports
7. Rewire WorkspaceManager + SessionReaper – inject ports
8. Rewire RAG module – inject MemoryRetrievalPort
9. Remove core.persistence package – verify empty
10. Add contract tests – validate any adapter implementation

**Do NOT start the adapter extraction until ED2, M9, and H9 are fixed. Otherwise you bake existing bugs into the new module boundary.**