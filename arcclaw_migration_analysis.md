## What the Opus Session Got Right (P95-Wave3.md) ✅

The architectural intent from the Wave 3 plan was executed correctly for structural concerns: 

| Completed | Evidence from conversation |
|---|---|
| Maven cyclic dependency (`arcclaw-core` ↔ `arcclaw-persistence-sqlite`) fixed | "Remove arcclaw-persistence-sqlite from arcclaw-core/pom.xml and add it to arcclaw-app/pom.xml" |
| `StatefulAgentStoreImpl` rewired to `CheckpointStorePort` | "Inject CheckpointStorePort, remove EntityManager, remove self proxy, remove withRetry" |
| `PersistenceRetryEngine` deleted & merged into adapter | Explicitly stated as complete |
| `CompactionSummaryRepository` → `SqliteCompactionStoreAdapter` + `CompactionStorePort` | All 5 callers updated |
| RAG module (`TwoTierMemoryService`) → `MemoryRetrievalPort` | Confirmed |
| Tests relocated to correct modules | Concurrency/retry tests moved to `arcclaw-persistence-sqlite` |
| Architecture Invariants updated (5.10.1, 5.14.1, 5.16, 5.18, 5.19) | Thorough additions |

***

## What the Opus Session Did NOT Fix 🔴

Cross-referencing my 8 critical findings against every file the Opus session explicitly edited or viewed — none of the following were touched:

### C1 · `markFailed(Long, String)` — `failReason` + `failedAt` STILL lost
The Opus session viewed `SqliteCheckpointStoreAdapter.java:265-285` and edited it, but the description says **"removed unused `cutoffDt` variable and updated LocalDateTime conversion for Instant"** — a diagnostic cleanup, not a `markFailed` fix.  The simple `markFailed(Long, String)` still calls only `transitionStatus(id, "FAILED")` with no `failReason` or `failedAt` written. 

### C2 · `markOrphaned` — Missing `failedAt`, `failReason`, and `CHECKPOINT_ORPHANED` audit event
Not mentioned anywhere in the session transcript.  The `CHECKPOINT_ORPHANED` audit events will still be silently swallowed. 

### C3 · Stale Join Guard removed from write path (HITL race condition)
The session viewed `SqliteCheckpointStoreAdapter.java:270-330` (exactly where `doSaveEntity` and `updateEntityFromDto` live) but the only edit described was the `LocalDateTime` fix.  The `updateEntityFromDto` method is still an unconditional field-copy. Per your own **Architecture Invariant 5.19**, the `findLatestAwaitingApprovalForCron` ORDER BY was mandated — but the Stale Join Guard protecting live `batchToolDecisions` from being overwritten during an `AWAITING_APPROVAL` save has no corresponding invariant and was not restored. 

### C4 · `saveAndFinalize` non-atomic — checkpoint + steps in two separate `REQUIRES_NEW` transactions
Not touched in the session. Still two independent transactions.  A failure in `storeAdapter.saveSteps()` leaves a committed checkpoint with no steps. 

### C5 · `SessionReaper.onStartup` startup retry stripped
`SessionReaper.java` was not edited in the Opus session — only its import of `SqliteRetryTemplate` was removed in the original diff.  The bare `QuarkusTransaction.requiringNew().run(this::doRecoverZombies)` with no BUSY retry is still in place. 

### C6 · `findLatestAwaitingApprovalForCron` uses exact match instead of LIKE prefix
Invariant **5.19** correctly mandates `ORDER BY c.id DESC` for all checkpoint queries, but says nothing about the LIKE vs. exact match issue.  The adapter's implementation still passes `"cron-" + cronId + "-"` to `findActiveBySession` which does `WHERE sessionId = :sid` (exact equality). Cron sessions with IDs like `cron-abc-<uuid>` will never be found. 

### C7 · `CHECKPOINT_CREATED` audit event missing from `save()` CREATE path
`StatefulAgentStoreImpl` was edited by the Opus session to use `CheckpointStorePort`, but the audit call for `SecurityEventType.CHECKPOINT_CREATED` that existed in the old `doSaveSteps` CREATE branch was not restored.  The `saveAndFinalize` path still has its audit, but new checkpoints created via `save()` will have no `CHECKPOINT_CREATED` event in the compliance trail. 

### C8 · `PersistenceAdapterPort` exposes retry concept — violates Invariant 5.14.1
The Wave 3 plan explicitly stated **"PersistenceRetryPort intentionally omitted — retry is adapter-internal"**.  Yet the implementation added `PersistenceAdapterPort.executeWithRetry(sessionId, eventType, Runnable)` to `arcclaw-persistence-api`. The method name `executeWithRetry` exposes a retry abstraction to `arcclaw-core` via the API module — this directly violates your own invariant 5.14.1: 

> *"Port interface MUST NOT contain PersistenceRetryPort or any retry abstraction"*

**Fix:** Rename to `execute(String sessionId, String eventType, Runnable dbAction)` — the retry is invisible inside `SqlitePersistenceAdapter`. Core shouldn't know retries exist.

***

## New Invariant Violations to Verify

Three invariants added in this session need to be manually verified against the final code:

### Invariant 5.18 — Terminal status exclusion in `doMarkCheckpointsFailed`
> *"MUST exclude `{FAILED, COMPLETED, ARCHIVED, COMPROMISED}` from the FAILED overwrite query"* 

**Verify:** `SqliteCheckpointStoreAdapter.doMarkCheckpointsFailed()` must use all 4 statuses. If it delegates to `CheckpointStatus.terminalStatuses()`, verify that set contains exactly these 4.

### Invariant 5.13 — Agroal pool configuration
> *"`background-validation=true`, `validation-query-sql=SELECT 1`, `max-size=3` MUST be present in application.properties for all non-test profiles"* 

**Verify:** The diff and session don't show any `application.yaml`/`application.properties` changes. These settings are mandatory — without `background-validation=true`, a single `SQLITE_BUSY` XA failure silently poisons the connection pool and breaks unrelated cron writes (the P84 cascade pattern).

### Invariant 5.19 — ORDER BY id DESC on all checkpoint queries
> *"findLatestAwaitingApprovalForCron MUST include ORDER BY c.id DESC LIMIT 1"* 

**Verify:** The adapter's `doFindLatestAwaitingApprovalForCron` — confirm both the `ORDER BY c.id DESC` **and** the `LIKE :prefix%` (not `= :prefix`) are present. The invariant covers ordering but the LIKE issue (C6) remains separate.

***

## Recommended Remaining Fix Order

These 8 items remain open — tackle them in this sequence to avoid introducing new issues:

1. **C3** — Add Stale Join Guard to `SqliteCheckpointStoreAdapter.updateEntityFromDto()` (AWAITING_APPROVAL status gate on `batchToolDecisions`)
2. **C4** — Combine `save` + `saveSteps` into a single `@Transactional(REQUIRES_NEW)` in the adapter as `saveCheckpointWithSteps(dto, steps)`
3. **C1** — Fix `markFailed(Long, String)`: `findById → build updated DTO with failedAt + failReason → save`
4. **C2** — Fix `markOrphaned(Long, String)`: same pattern + restore `auditService.recordWithIdempotency(CHECKPOINT_ORPHANED, ...)`
5. **C6** — Fix `findLatestAwaitingApprovalForCron`: change `findActiveBySession(pattern)` to a new port method `findLatestAwaitingApprovalWithPrefix(String sessionPrefix)` using `WHERE c.sessionId LIKE :prefix AND c.status = 'AWAITING_APPROVAL' ORDER BY c.id DESC`; use `AgentSentinels.CRON_SESSION_PREFIX`, not hardcoded `"cron-"`
6. **C7** — Restore `auditService.recordWithIdempotency(CHECKPOINT_CREATED, ...)` in `StatefulAgentStoreImpl.save()` INSERT branch
7. **C5** — Restore startup retry in `SessionReaper.onStartup` — add a simple retry loop (`for attempt in 0..5`) in `SessionReaper` since `doRecoverZombies` still uses `em` directly
8. **C8** — Rename `PersistenceAdapterPort.executeWithRetry` → `execute` to comply with invariant 5.14.1; verify HMAC boot-window retry is inside `SqlitePersistenceAdapter.execute()`

And the infra check: **verify `application.properties` has `background-validation=true`, `max-size=3`** per invariant 5.13 before any production deployment.

# ArcClaw Storage Backend Migration — Deep Analysis Report

## Architecture Intent (What the Diff Does)

The diff introduces a **Hexagonal / Ports-and-Adapters** structure:

- NEW `arcclaw-persistence-api` → Pure SPI interfaces + DTOs (CheckpointStorePort, CompactionStorePort, PersistenceAdapterPort, ReasoningStepDto, CheckpointDto, CompactionSummaryDto)
- NEW `arcclaw-persistence-sqlite` → SQLite adapter (SqliteCheckpointStoreAdapter, SqliteCompactionStoreAdapter) implementing those ports
- `arcclaw-core/pom.xml` → depends on `arcclaw-persistence-api` only; SQLite JDBC driver removed
- `arcclaw-app/pom.xml` → pulls in `arcclaw-persistence-sqlite` as concrete wiring

---

## 🔴 CRITICAL — Will Break Functionality

### C1 · `markFailed(Long, String)` — `failReason` + `failedAt` silently lost

**Old path:** `checkpoint.setFailedAt(Instant.now()) + checkpoint.setFailReason(reason) + em.merge`
**New path:** `storeAdapter.transitionStatus(checkpointId, "FAILED")`

`transitionStatus` only changes the `status` column. `failedAt` and `failReason` are never written.
Called from: `ApprovalStateMachine` (timeout), `AgentStateMachineRunner` (error paths), `CheckpointResumeHandler`.

The diagnostic overload `markFailed(Long, String, String, String, String)` is correct (uses `storeAdapter.findById → save full updated DTO`), but the simpler two-arg version that most callers use is broken.

**Fix:** Replace `transitionStatus(...)` with the same find-then-save pattern as the diagnostic overload.

---

### C2 · `markOrphaned(Long, String)` — Missing `failedAt`, `failReason`, AND missing audit event

**Old:** `checkpoint.setStatus(ORPHANED) + setFailedAt + setFailReason + auditService.recordWithIdempotency(CHECKPOINT_ORPHANED) + log.warn`
**New:** `storeAdapter.transitionStatus(checkpointId, "ORPHANED")`

Three things lost:
1. `failedAt` not set
2. `failReason` not set
3. **`SecurityEventType.CHECKPOINT_ORPHANED` audit event never fires** → compliance audit trail has a gap for every orphaned session

---

### C3 · Stale Join Guard REMOVED from write path (HITL approval race condition)

The A16 guard that prevented `saveAgentCheckpoint` from overwriting `{tool: APPROVED}` decisions with stale `{tool: PENDING}` data was:

```java
// OLD — in StatefulAgentStoreImpl.doSaveEntity (JPA managed entity path)
if (checkpoint.getStatus() == AWAITING_APPROVAL && managed.getStatus() == AWAITING_APPROVAL) {
    mergeBatchDecisions(managed, checkpoint);  // protects APPROVED decisions from being clobbered
} else {
    managed.setBatchToolDecisions(checkpoint.getBatchToolDecisions());
}
```

**New — `SqliteCheckpointStoreAdapter.updateEntityFromDto()`:**
```java
entity.setBatchToolDecisions(dto.batchToolDecisions);  // unconditional overwrite — guard GONE
```

The guard was moved to `StatefulAgentStoreImpl.dtoToEntity()` (READ path) but that is a no-op — a freshly created entity's fields come from the same DTO, so merging with itself changes nothing.

**Race condition path:** AgentStateMachineRunner calls `saveAgentCheckpoint(checkpoint)` with a checkpoint object that has `{tool: PENDING}` (loaded before the user approved). Meanwhile `ApproveReflex.updateBatchToolDecision()` already wrote `{tool: APPROVED}` in DB. The unconditional overwrite in `updateEntityFromDto` clobbers it back to PENDING → approval flow silently re-enters AWAITING_APPROVAL.

---

### C4 · `saveAndFinalize` — Checkpoint + Steps saved in TWO separate transactions (atomicity broken)

**Old:** `doSaveAndFinalize` was a single `@Transactional(REQUIRES_NEW)` that did:
  1. persist/merge checkpoint entity
  2. DELETE old ReasoningStepEntity rows
  3. INSERT new ReasoningStepEntity rows
  — all inside ONE transaction.

**New:**
```java
CheckpointDto saved = storeAdapter.save(entityToDto(checkpoint));    // TRANSACTION 1 — REQUIRES_NEW
storeAdapter.saveSteps(saved.id(), steps.stream()...toList());        // TRANSACTION 2 — REQUIRES_NEW
```

If `saveSteps` fails (e.g. disk full, BUSY timeout exhausted), the checkpoint is committed with no steps. On resume, `loadSteps(checkpointId)` returns empty → `deserializeSteps` also fails → agent loop has no history. This is a partial write that corrupts session state.

---

### C5 · `SessionReaper.onStartup` — SQLite BUSY retry stripped

**Old:** `retryTemplate.executeVoid("onStartup-zombies", 5, 50, () -> QuarkusTransaction.requiringNew().run(this::doRecoverZombies))`
**New:** `QuarkusTransaction.requiringNew().run(this::doRecoverZombies)`

`doRecoverZombies()` still uses direct `em.createQuery(...)`. If the previous JVM process hasn't fully released the SQLite file lock (common on crash/OOM), the very first JPQL query throws SQLiteException BUSY, the catch block logs at ERROR, and the app starts with zombie sessions stuck in IN_PROGRESS forever. With SQLite WAL mode the busy_timeout pragma helps, but on cold boot before WAL is configured it can still race.

---

### C6 · `findLatestAwaitingApprovalForCron` — LIKE prefix query replaced with exact match

**Old:**
```java
String sessionPrefix = AgentSentinels.CRON_SESSION_PREFIX + cronId;
q.setParameter("sessionPrefix", sessionPrefix + "%");  // LIKE 'cron-abc-%'
```

**New:**
```java
String pattern = "cron-" + cronId + "-";   // hardcoded prefix (not AgentSentinels.CRON_SESSION_PREFIX)
storeAdapter.findActiveBySession(pattern)   // WHERE sessionId = :sid  (exact match!)
```

`findActiveBySession` runs `WHERE c.sessionId = :sid AND c.status NOT IN (terminalStatuses)` — an **exact equality check**. Cron session IDs look like `cron-<cronId>-<uuid>`. The method is passed `"cron-<cronId>-"` which will NEVER match any real session ID. Every cron approval check returns empty → cron jobs never resume from AWAITING_APPROVAL.

Two bugs in one line: hardcoded prefix + wrong query type.

---

### C7 · `save(sessionId, agentRole, steps)` — `CHECKPOINT_CREATED` audit event missing

**Old `doSaveSteps` (CREATE branch):**
```java
auditService.recordWithIdempotency(sessionId, userId, agentRole, SecurityEventType.CHECKPOINT_CREATED, autonomy.toString(), idempotencyKey);
log.info("Created checkpoint for session %s...");
```

**New `save()`:** Delegates entirely to `storeAdapter.save(entityToDto(checkpoint))`. No audit call. The `saveAndFinalize` path still has its audit call, but the rolling-save path that fires on every reasoning iteration (upserts the checkpoint) has lost the CREATE-path audit event. New checkpoints created via `save()` won't appear in the compliance audit trail.

---

### C8 · HMAC boot-window retry — may be absent from `PersistenceAdapterPort.executeWithRetry`

**Old `PersistenceRetryEngine.persistSynchronously`** had an explicit 3-retry loop for `IllegalStateException("HMAC key unavailable")` to handle Vault key loading races on startup.

**New:** `persistenceAdapter.executeWithRetry(sessionId, eventType, dbAction)` — the `SqlitePersistenceAdapter.executeWithRetry` implementation must replicate this or audit writes will throw `ComplianceViolationException` during startup before Vault keys are loaded.
**(Needs explicit verification in the adapter's implementation.)**

---

## 🟡 HIGH — Behavioral Regression or Silent Data Change

### H1 · `findLatestRole` semantics changed

**Old:** Returns `agentRole` of the latest **ARCHIVED** checkpoint → always the most recently completed agent turn.
**New:** `storeAdapter.findLatestBySession(sessionId).map(dto -> dto.agentRole)` → returns the role of the absolute latest checkpoint **regardless of status**.

If the latest checkpoint is `IN_PROGRESS` or `AWAITING_APPROVAL`, `findLatestRole` now returns a different role than before. This affects `HistoryReflex`, `ResetReflex`, and any code that resolves the current agent role from history.

---

### H2 · `AsyncPersistenceEngine` — Direct sync save + queued async event (double-write risk)

```java
checkpointStore.saveCheckpoint(Mapper.toDto(checkpoint));  // synchronous save
submitCritical(event);                                       // queue another event
```

If `event.getDbAction()` is non-empty, the checkpoint is written TWICE — once synchronously, once via the queue. If the queue event fails with a retryable error, it will keep retrying on an already-committed write. If it succeeds, it may overwrite intermediate state. The `CompletableFuture` returned to the caller is gated on the queue event completing, not the sync save — so the caller blocks unnecessarily after the data is already committed.

---

### H3 · `StatefulAgentStoreImpl.dtoToEntity` — Stale Join Guard is a no-op

```java
if (CheckpointStatus.AWAITING_APPROVAL.toString().equals(dto.status)
    && entity.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
    mergeBatchDecisions(entity, dto);  // merges entity's decisions WITH the same dto they came from → no change
}
```

`entity.getStatus()` was set from `dto.status` 3 lines earlier. The condition is always equivalent to just checking `dto.status == AWAITING_APPROVAL`. The merge compares the entity against the same DTO, producing no change. This is dead code that gives a false sense of protection — see C3 above for where the real guard must live.

---

### H4 · Three diverging `entityToDto` mapper copies

1. `CheckpointMapper.toDto()` in `arcclaw-core` (used by `AsyncPersistenceEngine`)
2. `StatefulAgentStoreImpl.entityToDto()` (used by `StatefulAgentStoreImpl`)
3. `SqliteCheckpointStoreAdapter.entityToDto()` (used by adapter)

Any new field on `AgentCheckpoint` must be added in **three** places. They are already functionally identical — a single shared mapper in `arcclaw-persistence-api` (operating on the DTO record) would eliminate this. As-is, it is a guaranteed future desync bug.

---

## 🟠 MEDIUM — Architectural Leak / Future PostgreSQL Blocker

### M1 · `InstantToTextConverter` still in `arcclaw-core` — blocks PostgreSQL

`@Converter(autoApply=true)` on `InstantToTextConverter` applies globally to all JPA entities. SQLite stores `Instant` as ISO-8601 TEXT; PostgreSQL uses native `TIMESTAMPTZ`. Running with the Postgres adapter while this converter is auto-applied would cause type-mismatch on every timestamp column (`failedAt`, `completedAt`, `updatedAt`, etc.). Must be moved to `arcclaw-persistence-sqlite` and removed from auto-apply.

---

### M2 · `SqliteRetryTemplate` + `SqliteWalConfigurer` still in `arcclaw-core`

`SqliteCheckpointStoreAdapter` `@Inject`s `SqliteRetryTemplate`. Since the adapter depends on `arcclaw-core`, this works. But if a future `arcclaw-persistence-postgres` module is added and `arcclaw-persistence-sqlite` is excluded from the app classpath, `SqliteRetryTemplate` in `arcclaw-core` becomes an orphaned `@ApplicationScoped` bean that Quarkus will still try to instantiate (it requires the SQLite JDBC driver on classpath to work). Move both to `arcclaw-persistence-sqlite`.

---

### M3 · `SessionReaper.doRecoverZombies()` still bypasses adapter

Even with the new architecture, `doRecoverZombies()` uses `@Inject EntityManager em` directly with raw JPQL + `em.merge(checkpoint)` for setting FAILED status. This means:
- Retry semantics for zombie recovery are not delegated to the adapter
- Future adapters (Postgres) will work accidentally (JPA is dialect-agnostic) but the pattern is inconsistent
- Recommendation: add a `bulkMarkFailedByStatus(String sessionId, List<String> statuses, String reason)` method to `CheckpointStorePort` and delegate

---

### M4 · `parent pom.xml` — New modules not shown in diff

`arcclaw-persistence-api` and `arcclaw-persistence-sqlite` must appear in the root `<modules>` list. If omitted, `mvn clean install` from the project root skips these modules. **Must verify this was added.**

---

### M5 · `application.yaml` — SQLite datasource config location unclear

The old `arcclaw-core/src/main/resources/application.yaml` had:
```yaml
quarkus.datasource.db-kind: sqlite
quarkus.hibernate-orm.dialect: com.arcclaw.core.persistence.ArcClawSQLiteDialect
quarkus.datasource.jdbc.url: ${ARCCLAW_DATA_DIR}/arcclaw.db
```
The dialect class has moved to `arcclaw-persistence-sqlite`. The `application.yaml` must reference the new class name. If the old FQCN is still referenced, Quarkus boot fails with `ClassNotFoundException` for the dialect.

---

## ✅ Correctly Migrated (Confirmed Working)

| Concern | Status |
|---------|--------|
| `CompactionSummaryRepository` → `CompactionStorePort` | ✅ All 4 callers (CompactionService, CompactionSummaryCache, HistoryReflex, ResetReflex, StatusReflex) updated |
| `PersistenceRetryEngine` → `PersistenceAdapterPort.executeWithRetry` | ✅ Deleted, adapter encapsulates retry |
| `ArcClawSQLiteDialect` removed from `CoreReflectionConfig` | ✅ Native image config cleaned |
| `CompactionSummaryDto.messagesCompacted` accessor | ✅ Correct record field access |
| `CompactionService.upsert(new CompactionSummaryDto(...))` arg order | ✅ Matches record definition `(sessionId, summary, compactedAt, messagesCompacted, userId)` |
| `WorkspaceManager.markCheckpointsFailed` → adapter with retry | ✅ Adapter has `withRetry` wrapper |
| `deleteCheckpoint(sessionId, role)` → adapter | ✅ Delegated via `storeAdapter.deleteCheckpoint` |
| `isAllBatchToolsDecided`, `getBatchToolDecisions` | ✅ Reimplemented via `findBySessionAndRole` |
| `findStaleAwaitingApproval(cutoff)` → adapter | ✅ `storeAdapter.findStaleApprovals(cutoff)` |
| `markCompleted(Long)` — `completedAt` + audit | ✅ find-then-save pattern correct |
| `findByStatus(status)` | ✅ `storeAdapter.findByStatus(status.toString())` |
| `markCheckpointsFailed` `IllegalStateException` catch (JVM shutdown) | ✅ Preserved |
| `SqliteWalConfigurer` FTS ATTACH DATABASE | ✅ Moved to `arcclaw-persistence-sqlite` |
| `sleepQuietly` inlined in `AsyncPersistenceEngine` | ✅ Clean |
| RAG module (`SessionKnowledgeBase`, `GlobalKnowledgeBase`) moved to adapter | ✅ Architecture-correct |
| `FtsConnectionInterceptor` moved to adapter | ✅ Correct |
| `ArcClawSQLiteDialect` moved to adapter | ✅ Correct |

---

## Priority Fix Order

1. **C3** — Restore Stale Join Guard in `SqliteCheckpointStoreAdapter.updateEntityFromDto()`
2. **C4** — Make `saveAndFinalize` atomic: wrap `save` + `saveSteps` in one `@Transactional(REQUIRES_NEW)` in the adapter
3. **C1** — Fix `markFailed(Long, String)` to use find-then-save like the diagnostic overload
4. **C2** — Fix `markOrphaned` to set `failedAt`, `failReason`, and emit `CHECKPOINT_ORPHANED` audit event
5. **C6** — Fix `findLatestAwaitingApprovalForCron` to use a proper LIKE/prefix method on `CheckpointStorePort`
6. **C7** — Restore `auditService.recordWithIdempotency(CHECKPOINT_CREATED)` in `save()` CREATE path
7. **C5** — Restore startup retry for zombie recovery (add retry to `CheckpointStorePort.recoverZombies` or keep retry in `SessionReaper`)
8. **C8** — Verify HMAC boot-window retry in `SqlitePersistenceAdapter.executeWithRetry`
9. **H1** — Restore `findLatestRole` to filter for ARCHIVED status only
10. **H2** — Remove double-write in `AsyncPersistenceEngine` (sync save + queued event)
11. **M1** — Move `InstantToTextConverter` to `arcclaw-persistence-sqlite`
12. **M2** — Move `SqliteRetryTemplate` + `SqliteWalConfigurer` to `arcclaw-persistence-sqlite`
13. **H4** — Consolidate 3 mapper copies into one
14. **M3/M4/M5** — Verify parent pom modules list + application.yaml dialect class reference
