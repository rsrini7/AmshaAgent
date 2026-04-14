You are working on the ArcClaw project — a multi-module Quarkus agentic AI kernel. A git diff was recently applied that introduced a new persistence abstraction layer (`arcclaw-persistence-api` and `arcclaw-persistence-sqlite` modules) to make the storage backend swappable. The diff has been reviewed and **17 bugs were identified** — 5 critical runtime breaks, 6 high-severity bugs, and 6 low/medium issues. Your job is to fix ALL of them precisely. Do not refactor or change anything beyond the scope of each fix.
>
> ---
>
> ## CONTEXT
>
> - `arcclaw-persistence-api` — new module; pure port interfaces + DTOs, no SQLite dependency
> - `arcclaw-persistence-sqlite` — new module; all SQLite-specific code (adapters, FTS, WAL, dialect, retry)
> - `arcclaw-core` — backend-agnostic domain; must NOT contain SQLite-specific workarounds
> - `arcclaw-rag` — RAG module; must NOT import from `arcclaw-persistence-sqlite` directly
> - The new `CheckpointStorePort`, `CompactionStorePort`, `PersistenceAdapterPort`, `MemoryRetrievalPort` are the contracts; adapters live in the sqlite module
> - Data directory is wiped on next run — do NOT write migration scripts
>
> ---
>
> ## FIXES REQUIRED
>
> ### FIX 1 — CRITICAL: FTS Database Filename Mismatch (RUNTIME BREAK)
>
> **File:** `arcclaw-persistence-sqlite/.../SqliteWalConfigurer.java`
>
> **Problem:** `SqliteWalConfigurer` creates/attaches the FTS database as `arcclaw_fts.db` (underscore). `FtsConnectionInterceptor` (same module, fires on every Agroal pool borrow) attaches `arcclaw-fts.db` (hyphen). These point to two completely different files. All FTS/memory search will fail silently on every pooled connection.
>
> **Fix:** In `SqliteWalConfigurer`, change every occurrence of `arcclaw_fts.db` and `arcclaw_fts` (in both the `File` constructor and the `ATTACH DATABASE` SQL) to use hyphen: `arcclaw-fts.db` / `arcclaw-fts`. Do NOT touch `FtsConnectionInterceptor`.
>
> ---
>
> ### FIX 2 — CRITICAL: `compaction_summary_fts` Virtual Table Never Created (RUNTIME BREAK)
>
> **File:** `arcclaw-persistence-sqlite/.../SqliteWalConfigurer.java`
>
> **Problem:** The old `SqliteWalConfigurer` created the FTS5 virtual table consumed by `MemorySearchTool`, `HistoryReflex`, and `SqliteCompactionStoreAdapter.search()`. The new one does not. `RagSchemaInitializer` only creates the `fts.vec_global` vector table. All memory search returns empty on fresh installs.
>
> **Fix:** After the `ATTACH DATABASE` statement in `SqliteWalConfigurer`, add:
> ```java
> stmt.execute("CREATE VIRTUAL TABLE IF NOT EXISTS fts.compaction_summary_fts USING fts5(sessionId, summary)");
> ```
> Wrap in try/catch logging WARN on failure — never crash startup. Do NOT rename the table; `SqliteCompactionStoreAdapter` references it exactly.
>
> ---
>
> ### FIX 3 — CRITICAL: `SessionSchemaEnforcer` DDL Mismatch With Entity (RUNTIME BREAK)
>
> **Files:** `SessionSchemaEnforcer.java` (sqlite module) + `SessionRegistryEntry.java` (arcclaw-app)
>
> **Problem:** If `SessionRegistryEntry.@Table(name=...)` is `session_registry` but `SessionSchemaEnforcer` DDL creates `sessionregistry`, Hibernate manages one table while the enforcer creates a different orphaned one.
>
> **Fix:** Open both files. The entity is the source of truth. Make the `CREATE TABLE IF NOT EXISTS` name in `SessionSchemaEnforcer` exactly match the entity's `@Table(name=...)`. Also verify every column name in the DDL exactly matches the entity's `@Column(name=...)` values. Correct any mismatches in the DDL only.
>
> ---
>
> ### FIX 4 — CRITICAL: Missing `arcclaw-persistence-api` Dependency in `arcclaw-rag/pom.xml` (COMPILE FAILURE)
>
> **File:** `arcclaw-rag/pom.xml`
>
> **Problem:** The diff updated `TwoTierMemoryService` and `IngestDocumentTool` to import `MemoryChunkDto`, `MemoryRetrievalPort`, `MemoryEntryDto` from `com.arcclaw.persistence.api`, but `arcclaw-rag/pom.xml` has no such dependency. Module will not compile.
>
> **Fix:** Add to `arcclaw-rag/pom.xml`:
> ```xml
> <dependency>
>     <groupId>com.arcclaw</groupId>
>     <artifactId>arcclaw-persistence-api</artifactId>
>     <version>${project.version}</version>
> </dependency>
> ```
> Do NOT add `arcclaw-persistence-sqlite` — layering violation. Also audit `TwoTierMemoryService` and `RagContextEnricher` for any remaining direct imports of `GlobalKnowledgeBase` or `SessionKnowledgeBase` (moved to `arcclaw-persistence-sqlite`). Replace with `MemoryRetrievalPort` injection from the API module.
>
> ---
>
> ### FIX 5 — CRITICAL: `isSessionActive()` Misses `AWAITING_RETRY` — Silent Data Loss
>
> **File:** `arcclaw-persistence-sqlite/.../SqliteCheckpointStoreAdapter.java`, method `doIsSessionActive()`
>
> **Problem:** Current code uses an allowlist `List.of(IN_PROGRESS, AWAITING_APPROVAL)`. This misses `AWAITING_RETRY`. A rate-limited session will be reported inactive, causing SessionReaper/heartbeat tracker to terminate a live session.
>
> **Fix:** Replace the allowlist COUNT query with a denylist `NOT IN` query — future-proof for any new non-terminal statuses:
> ```java
> Long count = em.createQuery(
>     "SELECT COUNT(c) FROM AgentCheckpoint c WHERE c.sessionId = :sid " +
>     "AND c.status NOT IN :terminalStatuses", Long.class)
>     .setParameter("sid", sessionId)
>     .setParameter("terminalStatuses", CheckpointStatus.terminalStatuses())
>     .getSingleResult();
> return count > 0;
> ```
>
> ---
>
> ### FIX 6 — HIGH: NPE in `loadLatestAutonomyForSession()` on Null `autonomyLevel`
>
> **File:** `arcclaw-core/.../StatefulAgentStoreImpl.java`
>
> **Problem:** `AutonomyLevel.valueOf(d.autonomyLevel())` throws NPE when `autonomyLevel` is null (it is `@Nullable`).
>
> **Fix:**
> ```java
> return storeAdapter.findLatestBySession(sessionId)
>     .flatMap(d -> Optional.ofNullable(d.autonomyLevel())
>         .map(AutonomyLevel::valueOf));
> ```
>
> ---
>
> ### FIX 7 — HIGH: `AgentCronRepository` JPQL UPDATE `Instant` Params Bypass `autoApply` Converter
>
> **File:** `arcclaw-core/.../AgentCronRepository.java`
>
> **Problem:** Hibernate 6 bulk JPQL UPDATE queries bypass `@Converter(autoApply=true)` for parameter binding — a known ORM 6+ limitation explicitly acknowledged by a comment in `SessionReaper`. All cron scheduling methods (`markFired`, `markCompleted`, `markAwaitingApproval`, `markFailed`, `markSkipped`, `reclaimStaleCrons`) pass raw `Instant` parameters. These will write NULL or throw a binding error when cron fires.
>
> **Fix:** In every JPQL UPDATE method in `AgentCronRepository`, wrap all `Instant` parameters with `TemporalUtils.formatIsoLex(...)`:
> ```java
> // Before:
> .setParameter("last", lastFireTime)
> // After:
> .setParameter("last", TemporalUtils.formatIsoLex(lastFireTime))
> ```
> Apply to ALL `Instant` parameters in ALL bulk UPDATE queries in this class. SELECT queries are not affected.
>
> ---
>
> ### FIX 8 — HIGH: SQLite String Workaround in `arcclaw-core` Breaks Postgres (Architecture Violation)
>
> **Files:** `SessionReaper.java` (core), `CheckpointStorePort.java` (api), `SqliteCheckpointStoreAdapter.java` (sqlite)
>
> **Problem:** `SessionReaper.purgeOldChatHistory()` calls `TemporalUtils.formatIsoLex(cutoff)` to format the cutoff as a String before passing to JPQL — a SQLite-specific workaround embedded in backend-agnostic core. On Postgres, `updatedAt` is native `TIMESTAMPTZ` and the dialect would reject this String parameter.
>
> **Fix in 3 steps:**
>
> **Step A** — Add to `CheckpointStorePort`:
> ```java
> /** Deletes chat history for terminal sessions older than the cutoff. Backend handles temporal binding. */
> void purgeOldChatHistory(Instant cutoff);
> ```
>
> **Step B** — Implement in `SqliteCheckpointStoreAdapter` as a single `REQUIRES_NEW` retry-wrapped method. Move the bulk DELETE JPQL here. Keep `TemporalUtils.formatIsoLex()` **inside** this adapter method — that's where SQLite-specific concerns belong.
>
> **Step C** — In `SessionReaper.purgeOldChatHistory()`, replace the inline JPQL with:
> ```java
> Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
> log.infof("Purging chat history older than %d days (cutoff=%s)", retentionDays, cutoff);
> checkpointStore.purgeOldChatHistory(cutoff);
> ```
> Remove ALL `TemporalUtils.formatIsoLex()` calls from `SessionReaper`.
>
> ---
>
> ### FIX 9 — HIGH: Non-Atomic `markFailed` / `markCompleted` / `markOrphaned` (TOCTOU Race)
>
> **Files:** `StatefulAgentStoreImpl.java` (core), `CheckpointStorePort.java` (api), `SqliteCheckpointStoreAdapter.java` (sqlite)
>
> **Problem:** These three methods follow a `findById()` (Tx1) → build DTO → `save()` (Tx2) pattern. Between Tx1 and Tx2, another thread can update the same checkpoint. A checkpoint COMPLETED between T1 and T2 would be overwritten to FAILED.
>
> **Fix:** Add to `CheckpointStorePort`:
> ```java
> void markFailed(Long checkpointId, String failReason);
> void markCompleted(Long checkpointId);
> void markOrphaned(Long checkpointId);
> ```
> Implement each in `SqliteCheckpointStoreAdapter` as a single `@Transactional(TxType.REQUIRES_NEW)` method wrapping `em.clear()` + `em.find()` + field mutation (no separate save — the managed entity flushes automatically):
> ```java
> @Transactional(TxType.REQUIRES_NEW)
> public void doMarkFailed(Long checkpointId, String failReason) {
>     em.clear();
>     AgentCheckpoint cp = em.find(AgentCheckpoint.class, checkpointId);
>     if (cp == null) return;
>     cp.setStatus(CheckpointStatus.FAILED);
>     cp.setFailedAt(Instant.now());
>     cp.setFailReason(failReason);
> }
> ```
> Wrap with `withRetry()`. In `StatefulAgentStoreImpl`, replace the findById+save pattern with direct calls to these port methods.
>
> ---
>
> ### FIX 10 — HIGH: Non-Atomic `updateBatchToolDecision` Widens A16 Race Window
>
> **Files:** `StatefulAgentStoreImpl.java`, `CheckpointStorePort.java`, `SqliteCheckpointStoreAdapter.java`
>
> **Problem:** `findBySessionAndRole()` (Tx1) then `save(updated)` (Tx2) allows a concurrent approval callback for a different tool in the same batch to race. Two concurrent PENDING→decided writes to different tools in the same map may overwrite each other.
>
> **Fix:** Add to `CheckpointStorePort`:
> ```java
> boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision);
> ```
> Implement in `SqliteCheckpointStoreAdapter` as a single `REQUIRES_NEW` method that finds the managed entity directly (no DTO round-trip), mutates the JSON map in-place on the managed entity, and lets the transaction commit flush it. Call this from `StatefulAgentStoreImpl.updateBatchToolDecision()`, removing the two-step read-save pattern.
>
> ---
>
> ### FIX 11 — MEDIUM: Missing `beans.xml` in New Modules
>
> **Create:**
> - `arcclaw-persistence-api/src/main/resources/META-INF/beans.xml`
> - `arcclaw-persistence-sqlite/src/main/resources/META-INF/beans.xml`
>
> Content for both:
> ```xml
> <?xml version="1.0" encoding="UTF-8"?>
> <beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
>        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
>        xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
>            https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
>        version="4.0"
>        bean-discovery-mode="annotated">
> </beans>
> ```
>
> ---
>
> ### FIX 12 — LOW: Duplicate Port Method `findStaleAwaitingApproval`
>
> **File:** `CheckpointStorePort.java` and `SqliteCheckpointStoreAdapter.java`
>
> `findStaleAwaitingApproval(Instant cutoff)` and `findStaleApprovals(Instant cutoff)` both delegate to the same adapter implementation. Only `findStaleApprovals` is called from anywhere in the codebase. Remove `findStaleAwaitingApproval` from the port interface and its corresponding adapter entry point (the shared `doFindStaleAwaitingApproval` implementation is still used by `findStaleApprovals` — keep that).
>
> ---
>
> ### FIX 13 — LOW: `"ORPHANED"` Hardcoded String Literal
>
> **File:** `arcclaw-core/.../StatefulAgentStoreImpl.java`
>
> Replace the hardcoded `"ORPHANED"` string literal in `markOrphaned()` with `CheckpointStatus.ORPHANED.name()`.
>
> ---
>
> ## CONSTRAINTS — DO NOT VIOLATE
>
> - Do NOT modify `FtsConnectionInterceptor` — it is correct
> - Do NOT add `arcclaw-persistence-sqlite` as a dependency to `arcclaw-rag` or `arcclaw-core`
> - Do NOT write Flyway or Liquibase migration scripts
> - Do NOT alter the `PersistenceAdapterPort` interface beyond what Fix 8 specifies
> - `InstantToTextConverter` stays in `arcclaw-persistence-sqlite` — do NOT move back to core
> - `TemporalUtils` stays in `arcclaw-core` — it's a formatting utility, not SQLite-specific
> - Do NOT change any existing tests
> - `TemporalUtils.formatIsoLex()` calls belong ONLY in SQLite adapter methods and `AgentCronRepository` (which is a core class that needs this workaround specifically for Hibernate 6 + SQLite bulk UPDATE bypass)
>
> ## VERIFICATION CHECKLIST
>
> Confirm ALL of the following before closing:
> 1. `SqliteWalConfigurer` uses `arcclaw-fts.db` (hyphen) consistently
> 2. `SqliteWalConfigurer` creates `fts.compaction_summary_fts` after FTS attach
> 3. `SessionSchemaEnforcer` DDL table name exactly matches `SessionRegistryEntry.@Table(name=...)`
> 4. `arcclaw-rag/pom.xml` has `arcclaw-persistence-api`; zero imports from `com.arcclaw.persistence.sqlite` in `arcclaw-rag`
> 5. `doIsSessionActive` uses `NOT IN terminalStatuses` denylist
> 6. `loadLatestAutonomyForSession` uses `flatMap(d -> Optional.ofNullable(d.autonomyLevel()).map(AutonomyLevel::valueOf))`
> 7. All `Instant` parameters in all JPQL UPDATE queries in `AgentCronRepository` wrapped with `TemporalUtils.formatIsoLex()`
> 8. `SessionReaper` has zero `TemporalUtils.formatIsoLex()` calls — all delegated to `checkpointStore.purgeOldChatHistory(Instant)`
> 9. `markFailed`, `markCompleted`, `markOrphaned` in `StatefulAgentStoreImpl` are single port-method calls — no findById+save pattern
> 10. `updateBatchToolDecision` delegates to a single atomic `REQUIRES_NEW` adapter method
> 11. `beans.xml` exists in both new modules
> 12. `findStaleAwaitingApproval` removed from `CheckpointStorePort`
> 13. `"ORPHANED"` literal replaced with `CheckpointStatus.ORPHANED.name()`

***

## Notes on What I Intentionally Excluded From the Prompt

A few of Claude's findings I deliberately handled differently:

| Issue | Decision |
|---|---|
| #7 Null ID → `session-null` key collision | The actual diff shows `IngestDocumentTool` still uses `label` as the fallback key (not `entry.id()`). The Claude review described a slightly different version. Verify in your actual codebase — if `SqliteMemoryRetrievalAdapter.index()` uses `"session-" + entry.id()` and `MemoryEntryDto` is constructed with `null` id, add it as Fix 7b. The fix is: use `UUID.randomUUID().toString()` as the fallback key, never `entry.id()` which could be null. |
| #12 `LocalDateTime→Instant` data format change | You confirmed the data directory is wiped — moot, excluded. |
| #13 `runOnce` column rename | Same — data wiped, excluded. |
| #14 `RagContextEnricher` compile | Covered by Fix 4's "Also check" clause — if it directly imports concrete KB classes, Fix 4 handles it. |