This is a deep and careful review. I went through every file change in the diff against the original codebase. Here's what I found:

---

## BREAKING BUGS (will definitely fail at runtime)

### 1. FTS Database Filename Mismatch — Memory Search Broken

**New `SqliteWalConfigurer`** (in sqlite module) creates/attaches:
```java
File ftsDb = new File(dataDir, "arcclaw_fts.db");  // underscore
```

**`FtsConnectionInterceptor`** (moved, unchanged) attaches:
```java
ftsPath = Path.of(dataDir, "arcclaw-fts.db")...  // hyphen
```

These point to **two completely different SQLite files**. `FtsConnectionInterceptor` fires on every Agroal connection borrow and attaches the hyphen version. `SqliteWalConfigurer` attaches the underscore version at boot. The result: the FTS schema referenced in `SqliteCompactionStoreAdapter.syncFtsBestEffort()` and `search()` will not be found on pool connections. All memory search operations will silently fail or throw "no such table: fts.compaction_summary_fts".

---

### 2. `compaction_summary_fts` Virtual Table Never Created

The old `SqliteWalConfigurer` created the FTS table:
```java
stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts USING fts5(sessionId, summary)");
```

The new `SqliteWalConfigurer` does **not** create this table. `RagSchemaInitializer` (moved to sqlite module) creates vector/RAG tables, not this one. `MemorySearchTool`, `HistoryReflex`, and `SqliteCompactionStoreAdapter.search()` all depend on this table existing. `syncFtsBestEffort()` catches exceptions silently, so compaction writes won't fail — but all FTS-based memory search will return empty results on fresh installs.

---

### 3. `SessionSchemaEnforcer` DDL Doesn't Match `SessionRegistryEntry` Entity

New DDL in the sqlite module:
```sql
CREATE TABLE IF NOT EXISTS sessionregistry (
    channeltype TEXT NOT NULL,
    channelid   TEXT NOT NULL,
    userid      TEXT NOT NULL,
    sessionid   TEXT NOT NULL UNIQUE,
    createdat   TEXT NOT NULL,
    ...
)
```

But `SessionRegistryEntry` (unchanged in `arcclaw-app`) maps to:
```java
@Table(name = "session_registry", ...)
@Column(name = "channel_type")
@Column(name = "channel_id")
@Column(name = "session_id")
@Column(name = "created_at")
```

`Hibernate schema-management: update` will create/maintain `session_registry` from the JPA entity, while `SessionSchemaEnforcer` creates a separate orphaned `sessionregistry` table with wrong column names. Any native query using the DDL table would fail, and if some future code relies on `SessionSchemaEnforcer`'s table it will silently get the wrong schema.

---

### 4. Missing `arcclaw-rag/pom.xml` Dependency — Compile Failure

`arcclaw-rag` now has these imports added by the diff:

**`IngestDocumentTool.java`:**
```java
import com.arcclaw.persistence.api.MemoryEntryDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
```

**`TwoTierMemoryService.java`:**
```java
import com.arcclaw.persistence.api.MemoryChunkDto;
import com.arcclaw.persistence.api.MemoryRetrievalPort;
```

But `arcclaw-rag/pom.xml` is **not in the diff** — it has no `arcclaw-persistence-api` dependency added. The module will not compile. The same may apply to `RagContextEnricher` (also not in the diff) if it directly injected `GlobalKnowledgeBase` or `SessionKnowledgeBase` which are now moved to `arcclaw-persistence-sqlite`.

---

### 5. `isSessionActive()` Logic Regression — Wrong Status Set

**Original** in `StatefulAgentStoreImpl`:
```java
"AND c.status NOT IN (:terminalStatuses)"  // everything except COMPLETED,FAILED,LOOP_EXCEEDED,DENIED,COMPROMISED,ARCHIVED
```

**New** in `SqliteCheckpointStoreAdapter.doIsSessionActive()`:
```java
.setParameter("statuses", List.of(CheckpointStatus.IN_PROGRESS, CheckpointStatus.AWAITING_APPROVAL))
```

This misses `AWAITING_RETRY` (and `ORPHANED` if it's a non-terminal status). A session stuck in `AWAITING_RETRY` (rate-limited) will be reported as **inactive**, causing the `SessionReaper`, heartbeat tracker, and HITL logic to potentially terminate or clean up a session that is still in-flight waiting to resume. This is a silent data-loss bug.

---

## FUNCTIONAL REGRESSIONS (bugs that will manifest at runtime)

### 6. NPE in `loadLatestAutonomyForSession()` — Nullable Field

```java
return storeAdapter.findLatestBySession(sessionId).map(d -> AutonomyLevel.valueOf(d.autonomyLevel()));
```

`AgentCheckpoint.autonomyLevel` is `@Nullable`. `CheckpointDto.autonomyLevel()` can return null. `AutonomyLevel.valueOf(null)` throws `NullPointerException`. The original query used `.filter(Objects::nonNull)` to guard this. Fix: `Optional.ofNullable(d.autonomyLevel()).map(AutonomyLevel::valueOf)` — but note the outer `Optional` is already flat-mapping, so the full fix is:
```java
return storeAdapter.findLatestBySession(sessionId)
    .flatMap(d -> Optional.ofNullable(d.autonomyLevel()).map(AutonomyLevel::valueOf));
```

---

### 7. `IngestDocumentTool` — Null ID Causes Session KB Key Collision

```java
memoryPort.index(new MemoryEntryDto(null, text, Map.of("label", label)));
```

In `SqliteMemoryRetrievalAdapter.index()`:
```java
String chunkId = globalKb.ingest(entry.text());
sessionKb.ingest(
    chunkId != null ? chunkId : "session-" + entry.id(),  // entry.id() is null
    entry.text());
```

When `globalKb.ingest()` returns null (vector search unavailable), the session KB key becomes `"session-null"` for **every** ingested document. All subsequent ingestions to the session knowledge base will be stored under the same ID, overwriting each other. This silently loses ingested documents.

The original code returned `chunkId` in the success message (`"chunkId: " + chunkId`), which tests likely assert on. That assertion will now fail too.

---

### 8. `AgentCronRepository` JPQL UPDATE Queries — `Instant` Parameter Binding May Fail

The comment in `SessionReaper.purgeOldChatHistory()` explicitly acknowledges:
> "Hibernate 6 bulk JPQL often bypasses autoApply=true converters for parameters. We manually format to String using TemporalUtils."

But `AgentCronRepository` doesn't apply this fix to its JPQL UPDATE queries:

```java
em.createQuery(
    "UPDATE AgentCronEntity c SET c.status = :active, c.lastFireTime = :last, c.nextFireTime = :next, c.updatedAt = :now WHERE c.id = :id"
).setParameter("last", lastFireTime)     // raw Instant
 .setParameter("next", nextFireTime)     // raw Instant
 .setParameter("now", Instant.now())     // raw Instant
```

This affects `markFired`, `markCompleted`, `markAwaitingApproval`, `markFailed`, `markSkipped`, and `reclaimStaleCrons`. On Hibernate 6 with the SQLite dialect, `@Converter(autoApply=true)` is often bypassed for bulk JPQL UPDATE parameter binding. These operations will silently write NULL or throw a binding error at runtime when cron jobs fire.

---

### 9. SQLite-Specific String Parameter in Core Domain Code (`SessionReaper`)

```java
// SessionReaper.java (in arcclaw-core)
.setParameter("cutoff", TemporalUtils.formatIsoLex(cutoff))
```

This is a SQLite-specific workaround (passing a formatted `String` to an `Instant` JPQL parameter) hardcoded in `arcclaw-core` — the module that is supposed to be backend-agnostic. When a Postgres adapter is plugged in, `updatedAt` would be `TIMESTAMPTZ`, and Hibernate Postgres dialect would reject or misinterpret the `String` parameter for a native TIMESTAMP column. This directly violates the stated goal of storage swapability and contradicts §5.14.1.

---

### 10. Non-Atomic Read-Modify-Write in `markFailed/markCompleted/markOrphaned`

**Original:** Find + update + audit in a single `@Transactional(REQUIRES_NEW)` transaction.

**New `StatefulAgentStoreImpl.markCompleted()`:**
```java
storeAdapter.findById(checkpointId).ifPresent(dto -> {  // Transaction 1 (findById)
    CheckpointDto updated = new CheckpointDto(..., Instant.now(), ...);
    storeAdapter.save(updated);    // Transaction 2 (save)
    auditService.recordWithIdempotency(...);  // Transaction 3 (audit)
});
```

Between Transaction 1 and Transaction 2, another thread (e.g., approval callback + resume) could update the same checkpoint. The `save` in Transaction 2 would silently overwrite the intermediate state. For `markFailed` specifically, a checkpoint that was legitimately COMPLETED between the two transactions would be incorrectly marked FAILED.

---

### 11. Non-Atomic `updateBatchToolDecision` — A16 Guard Race Window Widened

Original: single `REQUIRES_NEW` — read existing decisions map, update one key, write back, all atomic.

New: `findBySessionAndRole` (Transaction 1) then `save(updated)` (Transaction 2). The adapter's `mergeBatchDecisions` in `updateEntityFromDto` provides some protection at save time, but between the read and the `save`, another approval callback could write its own decision. The adapter merge will protect against PENDING→non-PENDING regression, but cannot merge two concurrent non-PENDING updates to different keys correctly if they race.

---

## SCHEMA COMPATIBILITY BREAKS (data migration issues for upgrades)

### 12. `AgentCheckpoint.createdAt/updatedAt` — Data Format Change

**Original:** `LocalDateTime` stored via Hibernate's default SQLite mapping (format: `2025-01-15 10:30:00.123`).

**New:** `Instant` stored via `InstantToTextConverter(autoApply=true)` (format: `2025-01-15T10:30:00.123Z`).

Existing rows have the old format. `TemporalUtils.parseIsoLex()` uses `yyyy-MM-dd'T'HH:mm:ss.SSSX` which will throw `DateTimeParseException` reading existing rows. With `schema-management: update`, the column isn't recreated — existing data breaks silently or with a read exception. A Flyway migration script is needed for upgrades.

### 13. `AgentCronEntity.runOnce` Column Rename

Original field had no explicit `@Column(name=...)`, so Hibernate physical naming strategy produces `run_once`. New code has `@Column(name = "runonce")`. With `strategy: update`, Hibernate sees `runonce` as a missing column and attempts to add it as `NOT NULL` — which fails on existing rows (no DEFAULT value to backfill). The original `run_once` column becomes an orphan.

### 14. All `AgentCronEntity` `Instant` Fields — Format Change

`nextFireTime`, `lastFireTime`, `createdAt`, `updatedAt` all had explicit `@Convert(converter = InstantToTextConverter.class)` which used the same fixed-width ISO format. Those explicit annotations are removed and replaced by `autoApply=true`. Functionally the format is identical (both use `TemporalUtils.formatIsoLex`). But if any existing data was stored with the old `InstantToTextConverter` format from a previous version, the conversion is consistent — this is safe IF the format hasn't changed. The format is the same (`yyyy-MM-dd'T'HH:mm:ss.SSSX`), so this is fine for cron entity fields specifically.

---

## MISSING OR INCOMPLETE CHANGES

### 15. `RagContextEnricher` Not in Diff — Potential Compile Error

`GlobalKnowledgeBase` and `SessionKnowledgeBase` are moved to `arcclaw-persistence-sqlite`. `RagContextEnricher` (in `arcclaw-rag`, not in the diff) likely injects these classes for context enrichment. If it does, it will fail to compile since `arcclaw-rag` cannot depend on `arcclaw-persistence-sqlite` without creating a layering violation. This needs verification by running the full build.

### 16. No `beans.xml` in New Modules

`arcclaw-persistence-api` and `arcclaw-persistence-sqlite` have no `beans.xml` shown. Without it, strict CDI mode may not discover beans. The jandex plugin is present, so Quarkus's index-based discovery should work — but neither module is added to `quarkus.index-dependency` in `application.yaml`. Worth verifying CDI bean discovery is complete.

### 17. Duplicate Methods in `CheckpointStorePort`

`findStaleApprovals(Instant cutoff)` and `findStaleAwaitingApproval(Instant cutoff)` both exist on the port and both delegate to `doFindStaleAwaitingApproval` in the adapter. Only `findStaleApprovals` is called from `StatefulAgentStoreImpl`. `findStaleAwaitingApproval` on the port appears unused and should be removed to avoid confusion.

### 18. `"ORPHANED"` Status Hardcoded as String Literal

In `StatefulAgentStoreImpl.markOrphaned()`:
```java
"ORPHANED",  // hardcoded string
```

Should be `CheckpointStatus.ORPHANED.toString()` for refactor safety. If `CheckpointStatus.ORPHANED` is ever renamed, this breaks silently at runtime with an `IllegalArgumentException` from `CheckpointStatus.valueOf()` in the adapter.

---

## SUMMARY TABLE

| # | Issue | Severity | Component | Type |
|---|-------|----------|-----------|------|
| 1 | FTS DB filename mismatch (`_` vs `-`) | Critical | SqliteWalConfigurer / FtsConnectionInterceptor | Runtime break |
| 2 | `compaction_summary_fts` table never created | Critical | SqliteWalConfigurer | Runtime break |
| 3 | `SessionSchemaEnforcer` DDL wrong table/column names | Critical | SessionSchemaEnforcer | Runtime break |
| 4 | Missing `arcclaw-rag/pom.xml` dependency | Critical | arcclaw-rag | Compile failure |
| 5 | `isSessionActive()` misses `AWAITING_RETRY` status | Critical | SqliteCheckpointStoreAdapter | Data loss |
| 6 | NPE in `loadLatestAutonomyForSession()` | High | StatefulAgentStoreImpl | NPE |
| 7 | Null ID → `session-null` key collision in `IngestDocumentTool` | High | SqliteMemoryRetrievalAdapter | Data corruption |
| 8 | JPQL UPDATE `Instant` params bypass autoApply in `AgentCronRepository` | High | AgentCronRepository | Runtime failure |
| 9 | SQLite-specific String param in core domain (`SessionReaper`) | High | SessionReaper | Postgres break |
| 10 | Non-atomic `markFailed/markCompleted/markOrphaned` (TOCTOU) | High | StatefulAgentStoreImpl | Race condition |
| 11 | Non-atomic `updateBatchToolDecision` | High | StatefulAgentStoreImpl | A16 race |
| 12 | `AgentCheckpoint` `LocalDateTime→Instant` breaks existing data | Medium | AgentCheckpoint | Migration |
| 13 | `runOnce` column rename breaks existing data | Medium | AgentCronEntity | Migration |
| 14 | `RagContextEnricher` may reference moved classes | Medium | arcclaw-rag | Compile |
| 15 | No `beans.xml` in new modules | Low | arcclaw-persistence-* | CDI discovery |
| 16 | Duplicate port methods | Low | CheckpointStorePort | Design |
| 17 | `"ORPHANED"` hardcoded string | Low | StatefulAgentStoreImpl | Fragile |