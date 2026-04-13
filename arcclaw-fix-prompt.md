# ArcClaw — 8-Issue Fix Prompt for Wave 3 Post-Migration

> **Context for the AI assistant:**
> ArcClaw is a Quarkus 3.23+ multi-module Maven project. A Wave 3 refactoring was just completed
> that extracted all SQLite-specific persistence code out of `arcclaw-core` into a new
> `arcclaw-persistence-sqlite` module, with port interfaces in `arcclaw-persistence-api`.
> The migration was structurally correct but 8 specific bugs were either introduced or carried
> forward. This prompt provides the original working code for each issue and the exact fix needed.
> Apply all 8 fixes. Do not change any code outside the scope of each fix.

---

## Module layout (post-migration)

```
arcclaw-persistence-api/   ← port interfaces only; NO JPA, NO SQLite
  CheckpointStorePort.java
  MemoryRetrievalPort.java
  CompactionStorePort.java
  PersistenceAdapterPort.java
  BackendCapabilities.java
  dto/CheckpointDto.java

arcclaw-persistence-sqlite/ ← SQLite implementation; has EntityManager, SqliteRetryTemplate
  SqliteCheckpointStoreAdapter.java   implements CheckpointStorePort
  SqlitePersistenceAdapter.java       implements PersistenceAdapterPort
  SqliteRetryTemplate.java
  FtsConnectionInterceptor.java
  ArcClawSQLiteDialect.java
  SqliteErrorStrings.java
  SqliteWalConfigurer.java

arcclaw-core/               ← domain only; injects port interfaces, zero SQLite imports
  state/StatefulAgentStoreImpl.java
  state/AgentCheckpoint.java  (JPA entity — stays in core)
  state/CheckpointStatus.java
  lifecycle/WorkspaceManager.java
  lifecycle/SessionReaper.java
  audit/TelemetryAuditService.java
  audit/ComplianceAuditService.java
  persistence/AsyncPersistenceEngine.java
  persistence/CheckpointMapper.java
```

---

## FIX 1 — `markFailed(Long, String)` loses `failedAt`, `failReason`, and audit event

**File:** `arcclaw-persistence-sqlite/src/.../SqliteCheckpointStoreAdapter.java`

**Problem:**
The new adapter's `markFailed(Long id, String reason)` calls only
`transitionStatus(id, "FAILED")`. This drops `failedAt`, `failReason`, and the
`CHECKPOINT_FAILED` audit event. Callers like `RateLimitRetryScheduler`,
`AgentStateMachineRunner`, and `WorkspaceManager` all depend on `failReason` being
persisted for diagnostics and audit integrity.

**Original working logic (was in `StatefulAgentStoreImpl`):**
```java
// doMarkFailedId (REQUIRES_NEW)
em.clear();
AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
if (checkpoint != null) {
    checkpoint.setStatus(CheckpointStatus.FAILED);
    checkpoint.setFailedAt(Instant.now());
    checkpoint.setFailReason(reason);
    checkpoint.setErrorCode(errorCode);      // null is fine for 2-arg overload
    checkpoint.setTraceId(traceId);          // null is fine
    checkpoint.setRootCauseClass(rootCauseClass); // null is fine
    em.merge(checkpoint);
    String userId = WorkspaceContext.resolveUserId();
    String idempotencyKey = "CP-FAILED-" + checkpointId;
    String details = String.format("reason=%s errorCode=%s traceId=%s", reason, errorCode, traceId);
    auditService.recordWithIdempotency(checkpoint.getSessionId(), userId,
        checkpoint.getAgentRole(), SecurityEventType.CHECKPOINT_FAILED, details, idempotencyKey);
    log.warnf("Checkpoint %s/%s marked FAILED: %s", checkpoint.getSessionId(),
        checkpoint.getAgentRole(), reason);
}
```

**Fix:**
In `SqliteCheckpointStoreAdapter`, replace the current `markFailed(Long, String)` stub
with a proper `@Transactional(REQUIRES_NEW)` method that:
1. Does `em.clear()` first (SQLite L1 cache hygiene).
2. Loads the entity with `em.find(AgentCheckpoint.class, id)`.
3. Sets `status = FAILED`, `failedAt = Instant.now()`, `failReason = reason`.
4. Calls `em.merge(checkpoint)` inside the retry loop.
5. After the retry completes (outside the retry lambda), calls
   `auditService.recordWithIdempotency(...)` for `SecurityEventType.CHECKPOINT_FAILED`
   with idempotency key `"CP-FAILED-" + checkpointId`.

The same pattern must be applied to the **5-arg overload**
`markFailed(Long id, String reason, String errorCode, String traceId, String rootCauseClass)` —
all five fields must be written to the entity.

---

## FIX 2 — `markOrphaned(Long, String)` loses `failedAt`, `failReason`, and `CHECKPOINT_ORPHANED` audit event

**File:** `arcclaw-persistence-sqlite/src/.../SqliteCheckpointStoreAdapter.java`

**Problem:**
Same class, same pattern — `markOrphaned(Long id, String reason)` calls only
`transitionStatus(id, "ORPHANED")`. The `failedAt`, `failReason`, and the mandatory
`CHECKPOINT_ORPHANED` telemetry audit event are lost. `RateLimitRetryScheduler.sweepOrphanedRetries()`
calls `agentStore.markOrphaned(cp.getId(), reason)` and expects the full record to be written.

**Original working logic (was in `StatefulAgentStoreImpl`):**
```java
// doMarkOrphanedId (REQUIRES_NEW)
em.clear();
AgentCheckpoint checkpoint = em.find(AgentCheckpoint.class, checkpointId);
if (checkpoint != null) {
    checkpoint.setStatus(CheckpointStatus.ORPHANED);
    checkpoint.setFailedAt(Instant.now());
    checkpoint.setFailReason(reason);
    em.merge(checkpoint);
    String userId = WorkspaceContext.resolveUserId();
    String idempotencyKey = "CP-ORPHANED-" + checkpointId;
    auditService.recordWithIdempotency(checkpoint.getSessionId(), userId,
        checkpoint.getAgentRole(), SecurityEventType.CHECKPOINT_ORPHANED,
        reason, idempotencyKey);
    log.warnf("Checkpoint %s/%s marked ORPHANED: %s",
        checkpoint.getSessionId(), checkpoint.getAgentRole(), reason);
}
```

**Fix:**
In `SqliteCheckpointStoreAdapter`, replace the current `markOrphaned(Long, String)` stub with a
`@Transactional(REQUIRES_NEW)` method that mirrors the exact pattern above (em.clear → find →
set fields → merge → audit). The `idempotencyKey` must include the `checkpointId` to ensure
idempotency if the operation is retried on BUSY.

---

## FIX 3 — Stale Join Guard missing from `updateEntityFromDto` (HITL race condition)

**File:** `arcclaw-persistence-sqlite/src/.../SqliteCheckpointStoreAdapter.java`

**Problem:**
The new `updateEntityFromDto(AgentCheckpoint managed, CheckpointDto dto)` method copies all
fields unconditionally, including `batchToolDecisions`. This means if a human approves a tool
call and the approval writes `batchToolDecisions = {"readFile": "APPROVED"}`, and then the
agent loop performs a concurrent `save()` while still seeing the old `PENDING` value, the
approval decision is silently overwritten and lost. The original had an explicit guard called
"Stale Join Guard" (comment marker A16).

**Original working logic (was in `StatefulAgentStoreImpl.doSaveEntity`):**
```java
// A16 Stale Join Guard — merge batch decisions to prevent overwriting active decisions
if (checkpoint.getStatus() == CheckpointStatus.AWAITING_APPROVAL
        && managed.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
    mergeBatchDecisions(managed, checkpoint);
} else {
    managed.setBatchToolDecisions(checkpoint.getBatchToolDecisions());
}
```

Where `mergeBatchDecisions` was:
```java
private void mergeBatchDecisions(AgentCheckpoint managed, AgentCheckpoint incoming) {
    if (incoming.getBatchToolDecisions() == null) return;
    if (managed.getBatchToolDecisions() == null) {
        managed.setBatchToolDecisions(incoming.getBatchToolDecisions());
        return;
    }
    try {
        Map<String, ToolDecision> managedMap = objectMapper.readValue(
            managed.getBatchToolDecisions(), new TypeReference<>() {});
        Map<String, ToolDecision> incomingMap = objectMapper.readValue(
            incoming.getBatchToolDecisions(), new TypeReference<>() {});
        boolean changed = false;
        for (Map.Entry<String, ToolDecision> entry : managedMap.entrySet()) {
            String tool = entry.getKey();
            ToolDecision managedDecision = entry.getValue();
            ToolDecision incomingDecision = incomingMap.get(tool);
            // If managed has a real decision and incoming is still PENDING, keep the real decision
            if (ToolDecision.PENDING != managedDecision && ToolDecision.PENDING == incomingDecision) {
                incomingMap.put(tool, managedDecision);
                changed = true;
            }
        }
        if (changed) {
            managed.setBatchToolDecisions(objectMapper.writeValueAsString(incomingMap));
        } else {
            managed.setBatchToolDecisions(incoming.getBatchToolDecisions());
        }
    } catch (Exception e) {
        log.warnf("Failed to merge batch decisions for cp id=%d: %s",
            managed.getId(), e.getMessage());
        managed.setBatchToolDecisions(incoming.getBatchToolDecisions());
    }
}
```

**Fix:**
In `SqliteCheckpointStoreAdapter.updateEntityFromDto(AgentCheckpoint managed, CheckpointDto dto)`,
replace the unconditional `managed.setBatchToolDecisions(dto.getBatchToolDecisions())` line with
the guard logic above. You will need to adapt it to compare `dto.getStatus()` against
`CheckpointStatus.AWAITING_APPROVAL` and `managed.getStatus()` against the same. The
`mergeBatchDecisions` helper method must also be moved or replicated in this class. Inject
`ObjectMapper` into the adapter if not already present.

---

## FIX 4 — `saveAndFinalize` is non-atomic (two separate `REQUIRES_NEW` transactions)

**File:** `arcclaw-persistence-sqlite/src/.../SqliteCheckpointStoreAdapter.java`

**Problem:**
The current adapter calls `save(dto)` in one `@Transactional(REQUIRES_NEW)` block and then
`saveSteps(checkpointId, steps)` in a second separate `@Transactional(REQUIRES_NEW)` block.
If the JVM crashes or the DB returns BUSY between the two calls, the checkpoint row is committed
but has zero steps. `loadSteps(checkpointId)` then returns an empty list and the agent loop
restarts from scratch, losing the full reasoning trace.

**Original working logic (was in `StatefulAgentStoreImpl.doSaveAndFinalize`):**
```java
@Transactional(TxType.REQUIRES_NEW)
public AgentCheckpoint doSaveAndFinalize(String sessionId, String agentRole,
        List<ReasoningStep> steps, CheckpointStatus finalStatus) {
    em.clear();
    String json = serializeSteps(steps);
    Optional<AgentCheckpoint> existing = findBySessionAndRole(sessionId, agentRole);
    AgentCheckpoint checkpoint;
    if (existing.isPresent()) {
        checkpoint = existing.get();
        checkpoint.setStateJson(json);
        checkpoint.setStatus(finalStatus);
        if (finalStatus == CheckpointStatus.COMPLETED) checkpoint.setCompletedAt(Instant.now());
        else if (finalStatus == CheckpointStatus.FAILED) checkpoint.setFailedAt(Instant.now());
    } else {
        checkpoint = new AgentCheckpoint();
        checkpoint.setSessionId(sessionId);
        checkpoint.setAgentRole(agentRole);
        checkpoint.setStateJson(json);
        checkpoint.setStatus(finalStatus);
        if (finalStatus == CheckpointStatus.COMPLETED) checkpoint.setCompletedAt(Instant.now());
        else if (finalStatus == CheckpointStatus.FAILED) checkpoint.setFailedAt(Instant.now());
        em.persist(checkpoint);
        // Emit CHECKPOINT_CREATED audit for new checkpoints only
        String userId = WorkspaceContext.resolveUserId();
        String idempotencyKey = "CP-CREATED-" + sessionId + "-" + agentRole;
        auditService.recordWithIdempotency(sessionId, userId, agentRole,
            SecurityEventType.CHECKPOINT_CREATED, null, idempotencyKey);
        log.infof("Created new checkpoint for finalization session=%s, role=%s", sessionId, agentRole);
    }
    // ATOMIC: both checkpoint update AND step delete+insert in same REQUIRES_NEW tx
    final Long cpId = checkpoint.getId();
    em.createQuery("DELETE FROM ReasoningStepEntity r WHERE r.checkpointId = :id")
        .setParameter("id", cpId).executeUpdate();
    for (int i = 0; i < steps.size(); i++) {
        ReasoningStep step = steps.get(i);
        ReasoningStepEntity entity = new ReasoningStepEntity();
        entity.setCheckpointId(cpId);
        entity.setSequenceNum(i);
        entity.setThoughtJson(serialize(step.thought));
        entity.setAction(step.action);
        entity.setActionInputJson(serialize(step.actionInput));
        entity.setObservation(step.observation);
        entity.setInputTokens(step.inputTokens);
        entity.setOutputTokens(step.outputTokens);
        entity.setSuccess(step.success);
        entity.setErrorCode(step.errorCode);
        entity.setTraceId(step.traceId);
        entity.setRootCauseClass(step.rootCauseClass);
        em.persist(entity);
    }
    // Post-commit audit for final status
    String userId = WorkspaceContext.resolveUserId();
    SecurityEventType eventType = finalStatus == CheckpointStatus.COMPLETED
        ? SecurityEventType.CHECKPOINT_COMPLETED : SecurityEventType.CHECKPOINT_FAILED;
    String idempotencyKey = (finalStatus == CheckpointStatus.COMPLETED ? "CP-COMPLETED-" : "CP-FAILED-") + cpId;
    auditService.recordWithIdempotency(sessionId, userId, agentRole, eventType, null, idempotencyKey);
    log.infof("Checkpoint %s/%s finalized with status=%s", sessionId, agentRole, finalStatus);
    return checkpoint;
}
```

**Fix:**
In `SqliteCheckpointStoreAdapter`, add a single `saveCheckpointWithSteps` method annotated
`@Transactional(TxType.REQUIRES_NEW)` that contains the full atomic logic above (checkpoint
upsert + step DELETE + step INSERT in one transaction). The `saveAndFinalize` method on the
`CheckpointStorePort` interface should delegate to this combined method. The current two-call
pattern (`saveDto(...)` then `saveSteps(...)`) must be replaced with this single-transaction
approach for the `saveAndFinalize` path only. The regular `save(dto)` path can remain as-is.

---

## FIX 5 — `SessionReaper.onStartup` startup retry stripped

**File:** `arcclaw-core/src/.../lifecycle/SessionReaper.java`

**Problem:**
The refactoring removed the `@Inject SqliteRetryTemplate` from `SessionReaper`, which is
correct. However it also removed the retry wrapper around `doRecoverZombies()` in the
`onStartup` observer. The current code is:
```java
public void onStartup(@Observes StartupEvent event) {
    QuarkusTransaction.requiringNew().run(this::doRecoverZombies);
}
```
On a fresh start when SQLite's WAL file from an unclean shutdown has a high BUSY rate, this
single unguarded call can fail immediately with `SQLITE_BUSY`, silently dropping zombie
recovery entirely. No zombies get recovered, and sessions stay stuck in `IN_PROGRESS` forever.

**Fix:**
Wrap the startup recovery in a simple retry loop (no import of `SqliteRetryTemplate` — this
must stay backend-agnostic in `arcclaw-core`):

```java
public void onStartup(@Observes StartupEvent event) {
    int maxAttempts = 5;
    long delayMs = 100;
    Exception lastEx = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            QuarkusTransaction.requiringNew().run(this::doRecoverZombies);
            return; // success
        } catch (Exception e) {
            lastEx = e;
            log.warnf("onStartup zombie recovery attempt %d/%d failed: %s",
                attempt, maxAttempts, e.getMessage());
            if (attempt < maxAttempts) {
                try { Thread.sleep(delayMs * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    log.errorf(lastEx, "Failed onStartup zombie recovery after %d attempts", maxAttempts);
}
```

This keeps the retry logic in `arcclaw-core` as a plain Java loop (no SQLite dependency), and
the SQLite-specific BUSY handling inside the adapter's transaction boundary will still apply to
each individual attempt.

---

## FIX 6 — `findLatestAwaitingApprovalForCron` uses exact match instead of `LIKE` prefix scan

**File:** `arcclaw-persistence-sqlite/src/.../SqliteCheckpointStoreAdapter.java`
**Also touches:** `arcclaw-persistence-api/src/.../CheckpointStorePort.java`

**Problem:**
Cron sessions use IDs structured as `{CRON_SESSION_PREFIX}{cronId}-{executionUuid}` (e.g.,
`cron-daily-cleanup-550e8400-e29b-41d4-a716-446655440000`). The current adapter delegates to
`findActiveBySession(pattern)` which issues `WHERE c.sessionId = :sid` (exact equality).
An exact match will never find sessions where the UUID suffix varies.

**Original working JPQL (was in `StatefulAgentStoreImpl`):**
```java
String sessionPrefix = AgentSentinels.CRON_SESSION_PREFIX + cronId;
TypedQuery<AgentCheckpoint> q = em.createQuery(
    "SELECT c FROM AgentCheckpoint c " +
    "WHERE c.sessionId LIKE :sessionPrefix " +
    "AND c.status = :status " +
    "ORDER BY c.id DESC",
    AgentCheckpoint.class);
q.setParameter("sessionPrefix", sessionPrefix + "%");  // NOTE: % wildcard appended
q.setParameter("status", CheckpointStatus.AWAITING_APPROVAL);
q.setMaxResults(1);
return q.getResultList().stream().filter(Objects::nonNull).findFirst();
```

**Fix — two parts:**

**Part A** — Add a dedicated method to `CheckpointStorePort`:
```java
/** Find the most recent AWAITING_APPROVAL checkpoint for any session whose ID
 *  starts with the given prefix. Used for cron HITL approval lookups. */
Optional<AgentCheckpoint> findLatestAwaitingApprovalByPrefix(String sessionPrefix);
```

**Part B** — Implement in `SqliteCheckpointStoreAdapter`:
```java
@Override
@Transactional
public Optional<AgentCheckpoint> findLatestAwaitingApprovalByPrefix(String sessionPrefix) {
    // IMPORTANT: must append "%" to turn into a prefix scan, not exact match
    String likePattern = sessionPrefix + "%";
    return executeWithRetry("findLatestAwaitingApprovalByPrefix", 3, 50, () -> {
        em.clear();
        return em.createQuery(
            "SELECT c FROM AgentCheckpoint c " +
            "WHERE c.sessionId LIKE :prefix " +
            "AND c.status = :status " +
            "ORDER BY c.id DESC",
            AgentCheckpoint.class)
            .setParameter("prefix", likePattern)
            .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
            .setMaxResults(1)
            .getResultList().stream().filter(Objects::nonNull).findFirst();
    });
}
```

**Part C** — Update `StatefulAgentStoreImpl.findLatestAwaitingApprovalForCron(String cronId)` to call:
```java
String sessionPrefix = AgentSentinels.CRON_SESSION_PREFIX + cronId;
return checkpointStore.findLatestAwaitingApprovalByPrefix(sessionPrefix);
```
Do **NOT** hardcode `"cron-"` — always use `AgentSentinels.CRON_SESSION_PREFIX`.

---

## FIX 7 — `CHECKPOINT_CREATED` audit event missing from the `save()` INSERT path

**File:** `arcclaw-core/src/.../state/StatefulAgentStoreImpl.java`

**Problem:**
`StatefulAgentStoreImpl.save(sessionId, agentRole, steps)` calls
`checkpointStore.save(dto)` via the port. For the **INSERT** (new checkpoint) case, the
original code emitted a `SecurityEventType.CHECKPOINT_CREATED` audit event. The post-migration
`save()` does not emit this event, creating a gap in the compliance audit trail: new agent
sessions have no `CHECKPOINT_CREATED` entry.

**Original working logic (was in `StatefulAgentStoreImpl.doSaveSteps`, INSERT branch):**
```java
// Only for the CREATE path (existing.isEmpty()):
String userId = WorkspaceContext.resolveUserId();
String idempotencyKey = "CP-CREATED-" + sessionId + "-" + agentRole;
auditService.recordWithIdempotency(sessionId, userId, agentRole,
    SecurityEventType.CHECKPOINT_CREATED,
    autonomy != null ? autonomy.toString() : null,
    idempotencyKey);
log.infof("Created checkpoint for session=%s, role=%s, autonomy=%s", sessionId, agentRole, autonomy);
```

**Fix:**
In `StatefulAgentStoreImpl.save(String sessionId, String agentRole, List<ReasoningStep> steps)`,
after calling `checkpointStore.save(dto)`, check if this was a new checkpoint (the returned
`CheckpointDto` has a non-null `id` that was just generated, or the previous `load()` returned
empty). On the INSERT path, call:
```java
auditService.recordWithIdempotency(sessionId, userId, agentRole,
    SecurityEventType.CHECKPOINT_CREATED,
    autonomy != null ? autonomy.toString() : null,
    "CP-CREATED-" + sessionId + "-" + agentRole);
```

**Implementation note:** The simplest detection: call `checkpointStore.load(sessionId, agentRole)`
before the save. If `load()` returns empty → INSERT path → emit audit. This is consistent with
how the original `doSaveSteps` detected the INSERT branch. The audit call must be **outside** any
retry lambda (never inside a retried block) to prevent duplicate audit events on BUSY retry.

---

## FIX 8 — `PersistenceAdapterPort.executeWithRetry` name violates Architecture Invariant 5.14.1

**File:** `arcclaw-persistence-api/src/.../PersistenceAdapterPort.java`
**Also touches:** All callers in `arcclaw-core` that reference `executeWithRetry`

**Problem:**
Architecture Invariant 5.14.1 states:
> *"Port interfaces MUST NOT expose any retry abstraction. Retry is adapter-internal.
>  PersistenceRetryPort was intentionally omitted from the design."*

The current `PersistenceAdapterPort` has:
```java
void executeWithRetry(String sessionId, String eventType, Runnable dbAction);
```
The method name `executeWithRetry` leaks the concept of retrying into the API module.
`arcclaw-core` now knows that persistence involves retries — which is a SQLite-only concern.
A future PostgreSQL adapter wouldn't retry at all; calling `executeWithRetry` on it would be
semantically misleading.

**Fix — two parts:**

**Part A** — Rename the method in `PersistenceAdapterPort.java`:
```java
// BEFORE:
void executeWithRetry(String sessionId, String eventType, Runnable dbAction);

// AFTER:
void execute(String sessionId, String eventType, Runnable dbAction);
```

**Part B** — Update `SqlitePersistenceAdapter.java` to implement the renamed method:
```java
@Override
public void execute(String sessionId, String eventType, Runnable dbAction) {
    // Retry logic is HERE, inside the adapter — callers never see it
    retryTemplate.executeVoid(eventType, 5, 50, dbAction);
}
```

**Part C** — Update all callers in `arcclaw-core` (typically `ComplianceAuditService`,
`TelemetryAuditService`, `AsyncPersistenceEngine`) to call `persistenceAdapter.execute(...)`
instead of `persistenceAdapter.executeWithRetry(...)`.

**Part D** — Verify (do not change if already correct) that the HMAC-chain boot window
retry — the fallback when `AuditEventRepository` is not yet ready at startup — is inside
`SqlitePersistenceAdapter.execute()` and is NOT exposed through the port interface.

---

## Cross-cutting checks to run after all 8 fixes

After applying all fixes, verify the following by reading the relevant files:

1. **Invariant 5.18 — Terminal status set completeness:**
   `SqliteCheckpointStoreAdapter.doMarkCheckpointsFailed()` must exclude
   `{FAILED, COMPLETED, ARCHIVED, COMPROMISED}`. Verify that `CheckpointStatus.terminalStatuses()`
   returns exactly these 4 values and nothing else.

2. **Invariant 5.13 — Agroal pool configuration:**
   Verify `arcclaw-app/src/main/resources/application.properties` (or `application.yaml`) contains:
   ```yaml
   quarkus.datasource.jdbc.background-validation=true
   quarkus.datasource.jdbc.validation-query-sql=SELECT 1
   quarkus.datasource.jdbc.max-size=3
   ```
   If not present, add them. Without `background-validation=true`, a single SQLITE_BUSY XA
   failure silently poisons the Agroal pool for unrelated subsequent writes.

3. **No `executeWithRetry` string remaining:**
   After Fix 8, run a project-wide grep for `executeWithRetry`. Zero results expected.

4. **No direct `SqliteRetryTemplate` inject in `arcclaw-core`:**
   Run grep for `SqliteRetryTemplate` inside `arcclaw-core/src`. Zero results expected.

5. **No direct `EntityManager` inject in `arcclaw-core` domain classes:**
   Grep for `@Inject EntityManager` inside `arcclaw-core/src/main`. Allowed only in
   repositories (`AuditEventRepository`, `AgentCronRepository`, `AgentSkillRepository`,
   `EncryptedSecretRepository`) — NOT in `StatefulAgentStoreImpl`, `WorkspaceManager`,
   `SessionReaper`, `TwoTierMemoryService`, or `CompactionService`.

---

## Summary table

| # | File(s) | Root cause | Symptom if unfixed |
|---|---|---|---|
| 1 | `SqliteCheckpointStoreAdapter` | `markFailed` drops `failedAt`/`failReason`/audit | Failure details lost; no `CHECKPOINT_FAILED` audit event |
| 2 | `SqliteCheckpointStoreAdapter` | `markOrphaned` drops fields and audit | Orphaned sessions have no `CHECKPOINT_ORPHANED` event |
| 3 | `SqliteCheckpointStoreAdapter` | Stale Join Guard removed from `updateEntityFromDto` | Human approval decisions silently overwritten by agent `save()` |
| 4 | `SqliteCheckpointStoreAdapter` | `saveAndFinalize` splits across 2 transactions | Crash between calls leaves checkpoint with no steps |
| 5 | `SessionReaper` | Startup retry wrapper stripped | SQLITE_BUSY at boot drops zombie recovery silently |
| 6 | `SqliteCheckpointStoreAdapter` + port | Exact match instead of LIKE prefix | Cron HITL approvals never found; approval flow broken |
| 7 | `StatefulAgentStoreImpl` | `CHECKPOINT_CREATED` audit removed from `save()` INSERT path | New sessions invisible in compliance audit trail |
| 8 | `PersistenceAdapterPort` | Method named `executeWithRetry` leaks retry concept | Violates Invariant 5.14.1; misleads future Postgres adapter |

