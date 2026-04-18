## Tier 3 – Open P95 Items
> Discovered during Wave 3a storage extraction. Still open as of 2026-04-16.

### T3-1: SessionReaper EntityManager Import (P95-1)
* **Problem**: `SessionReaper` still imports `EntityManager` (line 13) for edge-case JPQL in `purgeOldChatHistory`. Should fully delegate to `CheckpointStorePort`.
* **File**: `arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/SessionReaper.java`
* **Severity**: LOW
* **Invariant**: §1.2 (core module boundary)
* **Done When**:
    * [ ] Zero `EntityManager` imports in `SessionReaper`
    * [ ] `purgeOldChatHistory` fully delegated to `CheckpointStorePort`

---

### T3-2: AgentCronRepository Direct EntityManager (P95-2)
* **Problem**: `AgentCronRepository` remains in `arcclaw-core` with direct `EntityManager` usage. All JPQL is standard JPA but uses `TemporalUtils.formatIsoLex()` workaround for Hibernate 6 bulk UPDATE.
* **File**: `arcclaw-core/src/main/java/com/arcclaw/core/scheduler/AgentCronRepository.java`
* **Severity**: MEDIUM
* **Invariant**: §1.2
* **Done When**:
    * [ ] `CronStorePort` interface created in `arcclaw-persistence-api`
    * [ ] SQLite adapter created in `arcclaw-persistence-sqlite`
    * [ ] `AgentCronRepository` in core delegates to port

---

### T3-3: PersistenceAdapterPort Leaks EntityManager (P95-3)
* **Problem**: `PersistenceAdapterPort.execute()` exposes `Consumer<EntityManager>` — JPA type leaks into the port API.
* **File**: `arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceAdapterPort.java`
* **Severity**: MEDIUM
* **Invariant**: §5.14.1 (port purity)
* **Done When**:
    * [ ] Port interface uses an abstraction (e.g., `Consumer<Object>` or typed callback) instead of `EntityManager`
    * [ ] `AsyncPersistenceEngine` updated accordingly

---

### T3-4: WORM Audit Triggers for Postgres (P95-7)
* **Problem**: `AuditSchemaEnforcer` implements WORM triggers as SQLite-specific DDL. No Postgres equivalent exists yet for future `arcclaw-persistence-postgres`.
* **File**: `arcclaw-persistence-sqlite/src/main/java/com/arcclaw/persistence/sqlite/AuditSchemaEnforcer.java`
* **Severity**: MEDIUM
* **Invariant**: §5.23 (DDL isolation)
* **Done When**:
    * [ ] Postgres WORM triggers implemented (via RLS or triggers) when `arcclaw-persistence-postgres` is created
    * [ ] Test verifies immutability of `audit_event` table across both backends

---

### T3-5: CheckpointMapper Lockstep Risk (P95-9)
* **Problem**: `CheckpointMapper` does bidirectional mapping between `AgentCheckpoint` entity and `CheckpointDto`. Entity field changes require mapper updates in lockstep — no compile-time enforcement.
* **File**: `arcclaw-core/src/main/java/com/arcclaw/core/persistence/CheckpointMapper.java`
* **Severity**: LOW
* **Done When**:
    * [ ] Either: MapStruct generates the mapper (compile-time field checking) **OR**
    * [ ] A dedicated `CheckpointMapperTest` verifies round-trip for all fields

---

Prompt:

You are working on the ArcClaw project (Quarkus 3.x, Java 21, virtual threads, SQLite WAL).
Execute the five Tier-3 open items below in the order listed. For each item, apply the listed
invariant guards before committing. Do NOT merge items or skip guards.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ITEM 1 of 5 — T3-1: Remove EntityManager from SessionReaper
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/SessionReaper.java

TASK:
1. Remove the `@Inject EntityManager em` field (line 13) from SessionReaper.
2. Verify that `purgeOldChatHistory` already delegates entirely to
   `checkpointStore.purgeOldChatHistory(cutoff)` — it does. No logic change needed.
3. Confirm no other method in SessionReaper references `em`. If any does, report it
   but DO NOT fix it in this item — that is out of scope.

INVARIANT GUARDS (check all before committing):
- §1.2: Zero `EntityManager` imports in
  `com.arcclaw.core.lifecycle.SessionReaper` after this change.
- SCOPE BOUNDARY: WorkspaceManager also injects EntityManager — that is a
  separate debt item. Do NOT touch WorkspaceManager here.

DONE WHEN:
[ ] `SessionReaper.java` has no `EntityManager` import or field.
[ ] `purgeOldChatHistory` compiles and delegates to CheckpointStorePort only.
[ ] No other production code in the lifecycle package changed.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ITEM 2 of 5 — T3-5: CheckpointMapper round-trip test
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-core/src/main/java/com/arcclaw/core/persistence/CheckpointMapper.java
New file: arcclaw-core/src/test/java/com/arcclaw/core/persistence/CheckpointMapperTest.java

TASK:
Create `CheckpointMapperTest` with the following two tests. Do NOT modify
CheckpointMapper itself. Do NOT add MapStruct (annotation processors interact
with GraalVM native image registration — §6.1 risk, deferred).

```java
// arcclaw-core/src/test/java/com/arcclaw/core/persistence/CheckpointMapperTest.java
package com.arcclaw.core.persistence;

import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.persistence.api.CheckpointDto;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointMapperTest {

    @Test
    void roundTrip_allFieldsPreserved() {
        AgentCheckpoint entity = new AgentCheckpoint();
        entity.setId(42L);
        entity.setSessionId("sess-roundtrip");
        entity.setAgentRole("ORCHESTRATOR");
        entity.setStatus(CheckpointStatus.INPROGRESS);
        entity.setAutonomyLevel(AutonomyLevel.SUPERVISED);
        entity.setStateJson("{\"steps\":[]}");
        entity.setChannelPlatformId("user-123");
        entity.setChannelType("websocket");
        entity.setOriginalGoal("Do the thing");
        entity.setFailedAt(null);
        entity.setCompletedAt(null);
        entity.setFailReason(null);
        entity.setPendingToolName("bash");
        entity.setBatchToolDecisions("{\"bash\":\"PENDING\"}");
        entity.setPendingToolParametersJson("{\"cmd\":\"ls\"}");
        entity.setPreExecutedResults("{\"bash\":\"ok\"}");
        entity.setMaxIterations(10);
        entity.setIterationsUsed(3);
        entity.setRetryAfterMs(500L);
        entity.setSystemNote("note");
        entity.setErrorCode("ERR_001");
        entity.setTraceId("trace-abc");
        entity.setRootCauseClass("SomeException");
        entity.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2025-01-02T00:00:00Z"));

        CheckpointDto dto = CheckpointMapper.toDto(entity);
        AgentCheckpoint roundTripped = CheckpointMapper.toEntity(dto);

        assertEquals(entity.getId(),                  roundTripped.getId());
        assertEquals(entity.getSessionId(),            roundTripped.getSessionId());
        assertEquals(entity.getAgentRole(),            roundTripped.getAgentRole());
        assertEquals(entity.getStatus(),               roundTripped.getStatus());
        assertEquals(entity.getAutonomyLevel(),        roundTripped.getAutonomyLevel());
        assertEquals(entity.getStateJson(),            roundTripped.getStateJson());
        assertEquals(entity.getChannelPlatformId(),    roundTripped.getChannelPlatformId());
        assertEquals(entity.getChannelType(),          roundTripped.getChannelType());
        assertEquals(entity.getOriginalGoal(),         roundTripped.getOriginalGoal());
        assertEquals(entity.getPendingToolName(),      roundTripped.getPendingToolName());
        assertEquals(entity.getBatchToolDecisions(),   roundTripped.getBatchToolDecisions());
        assertEquals(entity.getPendingToolParametersJson(), roundTripped.getPendingToolParametersJson());
        assertEquals(entity.getPreExecutedResults(),   roundTripped.getPreExecutedResults());
        assertEquals(entity.getMaxIterations(),        roundTripped.getMaxIterations());
        assertEquals(entity.getIterationsUsed(),       roundTripped.getIterationsUsed());
        assertEquals(entity.getRetryAfterMs(),         roundTripped.getRetryAfterMs());
        assertEquals(entity.getSystemNote(),           roundTripped.getSystemNote());
        assertEquals(entity.getErrorCode(),            roundTripped.getErrorCode());
        assertEquals(entity.getTraceId(),              roundTripped.getTraceId());
        assertEquals(entity.getRootCauseClass(),       roundTripped.getRootCauseClass());
        assertEquals(entity.getCreatedAt(),            roundTripped.getCreatedAt());
        assertEquals(entity.getUpdatedAt(),            roundTripped.getUpdatedAt());
    }

    @Test
    void roundTrip_nullFieldsSafe() {
        AgentCheckpoint sparse = new AgentCheckpoint();
        sparse.setId(1L);
        sparse.setSessionId("sess-sparse");
        // All other fields null — verifies no NPE in mapper
        assertDoesNotThrow(() -> CheckpointMapper.toDto(sparse));
    }
}
```

INVARIANT GUARDS:
- §16.8: Use `org.junit.jupiter.api.Assertions` only. Do NOT use AssertJ.
  Do NOT use `assertThat` anywhere in this test.
- The test must be a plain JUnit 5 unit test — NO `@QuarkusTest` annotation.
  CheckpointMapper has zero CDI dependencies; a full Quarkus container is unnecessary.

DONE WHEN:
[ ] `CheckpointMapperTest` has two tests: `roundTrip_allFieldsPreserved` and
    `roundTrip_nullFieldsSafe`.
[ ] Both tests pass with `mvn test -pl arcclaw-core -Dtest=CheckpointMapperTest`.
[ ] No MapStruct dependency added.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ITEM 3 of 5 — T3-3: Remove EntityManager leak from PersistenceAdapterPort
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Files touched:
  arcclaw-persistence-api  — add PersistenceAction.java, PersistenceContext.java,
                             update PersistenceAdapterPort.java
  arcclaw-core             — update PersistenceEvent.java, AsyncPersistenceEngine.java
  arcclaw-persistence-sqlite — update SqlitePersistenceAdapter.java (wraps EntityManager
                               in PersistenceContext internally)

TASK — Step 1: Create the abstraction in arcclaw-persistence-api

```java
// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceContext.java
package com.arcclaw.persistence.api;

/**
 * Minimal persistence surface exposed to arcclaw-core.
 * Hides the JPA EntityManager from all core code (§5.14.1).
 */
public interface PersistenceContext {
    void persist(Object entity);
    void merge(Object entity);
    void remove(Object entity);
    <T> T find(Class<T> type, Object id);
}
```

```java
// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceAction.java
package com.arcclaw.persistence.api;

/**
 * Replaces Consumer<EntityManager> in PersistenceAdapterPort.
 * Core code constructs PersistenceAction lambdas without importing EntityManager.
 */
@FunctionalInterface
public interface PersistenceAction {
    void execute(PersistenceContext ctx);
}
```

Update `PersistenceAdapterPort`:
```java
// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/PersistenceAdapterPort.java
package com.arcclaw.persistence.api;

public interface PersistenceAdapterPort {
    void execute(String sessionId, String eventType, PersistenceAction action);
}
```

TASK — Step 2: Update PersistenceEvent in arcclaw-core

In `PersistenceEvent.java`:
- Change the `dbAction` field type from `Consumer<EntityManager>` to `PersistenceAction`.
- Update the constructor, `PersistenceEvent.critical(...)`, and
  `PersistenceEvent.telemetry(...)` factory methods accordingly.
- Remove the `import jakarta.persistence.EntityManager` and
  `import java.util.function.Consumer` imports if they are now unused.

The existing call site in `AsyncPersistenceEngine` that builds events like:
  `PersistenceEvent.critical(checkpoint.getSessionId(), ..., em -> em.merge(checkpoint))`
must be updated to:
  `PersistenceEvent.critical(checkpoint.getSessionId(), ..., ctx -> ctx.merge(checkpoint))`

TASK — Step 3: Update SqlitePersistenceAdapter in arcclaw-persistence-sqlite

The adapter receives the `PersistenceAction` callback from core and executes it by
wrapping the real `EntityManager` in an anonymous `PersistenceContext`:

```java
// Inside SqlitePersistenceAdapter.execute(...)
@Override
public void execute(String sessionId, String eventType, PersistenceAction action) {
    // EntityManager em is injected here — stays INSIDE the sqlite adapter only
    action.execute(new PersistenceContext() {
        @Override public void persist(Object entity) { em.persist(entity); }
        @Override public void merge(Object entity)   { em.merge(entity); }
        @Override public void remove(Object entity)  { em.remove(entity); }
        @Override public <T> T find(Class<T> type, Object id) { return em.find(type, id); }
    });
}
```

INVARIANT GUARDS (verify all before committing):
- §5.14.1: After this change, zero `EntityManager` or `jakarta.persistence.*`
  imports must remain in arcclaw-core or arcclaw-persistence-api. Run:
    `grep -r "EntityManager" arcclaw-core/src arcclaw-persistence-api/src`
  Expected result: zero matches (PersistenceEvent, AsyncPersistenceEngine, etc.).
- §5.14.1: Do NOT expose `SqliteRetryTemplate` through `PersistenceContext` or
  any port method. Retry stays internal to arcclaw-persistence-sqlite.
- §5.17: The drain thread's per-event `WorkspaceScopeHolder.run(event.getSessionId(), ...)`
  binding in `AsyncPersistenceEngine.processEvent` MUST NOT change. Only the
  callback type changes (`Consumer<EntityManager>` → `PersistenceAction`).
- §5.8: `SqlitePersistenceAdapter.execute` must NOT be annotated
  `@Transactional(REQUIRED)`. The retry and transaction boundary stay in the
  existing `persistSynchronously` / `SqliteRetryTemplate` path already present
  in arcclaw-persistence-sqlite.

DONE WHEN:
[ ] Zero `EntityManager` imports in arcclaw-core and arcclaw-persistence-api.
[ ] `PersistenceAction` and `PersistenceContext` live in arcclaw-persistence-api.
[ ] `PersistenceEvent.dbAction` is typed `PersistenceAction`.
[ ] `SqlitePersistenceAdapter` wraps `EntityManager` in anonymous `PersistenceContext`.
[ ] `mvn test -pl arcclaw-core,arcclaw-persistence-sqlite` passes.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ITEM 4 of 5 — T3-2: AgentCronRepository → CronStorePort
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Files touched:
  arcclaw-persistence-api    — add CronStorePort.java, AgentCronDto.java (if not present)
  arcclaw-persistence-sqlite — add SqliteCronStoreAdapter.java
  arcclaw-core               — refactor AgentCronRepository.java to delegate to CronStorePort

TASK — Step 1: Create CronStorePort in arcclaw-persistence-api

```java
package com.arcclaw.persistence.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CronStorePort {
    List<AgentCronDto> findDueJobs(Instant now);
    void updateNextFireTime(long cronId, String nextFireLex);  // TemporalUtils stays in adapter
    void reclaimStaleCrons();
    void claimForFire(long cronId, String deliveryId);
    Optional<AgentCronDto> findById(long cronId);
    void save(AgentCronDto dto);
    void deleteById(long cronId);
    List<AgentCronDto> findBySessionId(String sessionId);
}
```

Create `AgentCronDto` as a plain record in arcclaw-persistence-api if it does not
already exist. Mirror all fields from the `AgentCron` JPA entity. No JPA annotations
on the DTO.

TASK — Step 2: Create SqliteCronStoreAdapter in arcclaw-persistence-sqlite

- Move all EntityManager JPQL verbatim from `AgentCronRepository` into this adapter.
- `TemporalUtils.formatIsoLex()` workaround stays here — it is SQLite-specific and
  MUST NOT cross into the port or core.
- Annotate write methods:
  - `reclaimStaleCrons()`:  `@Transactional(REQUIRES_NEW)` — isolated, per §5.12
  - `claimForFire(...)`:    `@Transactional(REQUIRES_NEW)`
  - `updateNextFireTime(…)`: `@Transactional(REQUIRES_NEW)`
  - `save(...)`:             `@Transactional(REQUIRES_NEW)`
  - `deleteById(...)`:       `@Transactional(REQUIRES_NEW)`
  - `findDueJobs(...)`:      `@Transactional(Transactional.TxType.SUPPORTS)` (read)
  - `findById(...)`:         `@Transactional(Transactional.TxType.SUPPORTS)` (read)
  - `findBySessionId(...)`:  `@Transactional(Transactional.TxType.SUPPORTS)` (read)

TASK — Step 3: Refactor AgentCronRepository in arcclaw-core

- Replace `@Inject EntityManager em` with `@Inject CronStorePort cronStore`.
- All methods become pure delegates (one-line calls to `cronStore.*`). Zero JPQL in core.
- `AgentCronScheduler` continues injecting `AgentCronRepository` — no caller changes needed.

INVARIANT GUARDS (verify all before committing):
- §1.2: Zero `EntityManager` imports in `AgentCronRepository` after refactor.
- §5.12: `AgentCronScheduler.pollAndDispatch` MUST remain `@Transactional(NOT_SUPPORTED)`.
  Do NOT change it. `reclaimStaleCrons` in the adapter is its own `REQUIRES_NEW`
  method, NOT folded into a larger method.
- §5.12: The `reclaimStaleCrons` isolation try-catch in `AgentCronScheduler.pollAndDispatch`
  MUST remain intact:
    ```java
    try { cronRepository.reclaimStaleCrons(); }
    catch (Exception e) { log.warn(...); return; }
    ```
  Do NOT remove or widen this catch block.
- §5.8: Confirm `SqliteCronStoreAdapter` write methods use `REQUIRES_NEW`, not `REQUIRED`.
  A `REQUIRED` here combined with an outer `NOT_SUPPORTED` in the scheduler is safe
  but non-idiomatic — still flag and leave as `REQUIRES_NEW`.
- TemporalUtils usage: Confirm `TemporalUtils.formatIsoLex()` is used only inside
  `SqliteCronStoreAdapter`, never in `AgentCronRepository` or `CronStorePort`.

DONE WHEN:
[ ] `CronStorePort` interface in arcclaw-persistence-api.
[ ] `SqliteCronStoreAdapter` in arcclaw-persistence-sqlite with all JPQL.
[ ] `AgentCronRepository` in core is a pure delegate, zero `EntityManager` imports.
[ ] `AgentCronScheduler.pollAndDispatch` annotation and reclaimStaleCrons try-catch unchanged.
[ ] `mvn test -pl arcclaw-core,arcclaw-persistence-sqlite` passes.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ITEM 5 of 5 — T3-4: WORM Audit Trigger pre-work (Postgres deferred gate)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
File: arcclaw-persistence-sqlite/src/main/java/com/arcclaw/persistence/sqlite/AuditSchemaEnforcer.java
New file: arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/AuditSchemaPort.java

TASK — Step 1: Add a deferred-implementation comment to AuditSchemaEnforcer

At the top of the `enforceWormConstraints()` method body, add exactly:

```java
// Postgres equivalent (implement in PostgresAuditSchemaEnforcer when arcclaw-persistence-postgres is created):
//   REVOKE UPDATE, DELETE ON audit_event FROM application_role;
//   -- OR --
//   CREATE RULE no_update AS ON UPDATE TO audit_event DO INSTEAD NOTHING;
//   CREATE RULE no_delete AS ON DELETE TO audit_event DO INSTEAD NOTHING;
// Both approaches satisfy §5.23 DDL isolation — choose per deployment policy.
```

TASK — Step 2: Extract AuditSchemaPort in arcclaw-persistence-api

```java
// arcclaw-persistence-api/src/main/java/com/arcclaw/persistence/api/AuditSchemaPort.java
package com.arcclaw.persistence.api;

/**
 * Adapter contract for enforcing WORM (write-once read-many) constraints on the
 * audit_event table. Each persistence adapter implements backend-specific DDL.
 * §5.23: DDL isolation — implementations live in adapter modules only.
 */
public interface AuditSchemaPort {
    /**
     * Enforce WORM constraints for the audit_event table.
     * Called once at startup by the adapter's @PostConstruct or Flyway callback.
     */
    void enforceWormConstraints();
}
```

Have `AuditSchemaEnforcer` implement `AuditSchemaPort`:
```java
@ApplicationScoped
public class AuditSchemaEnforcer implements AuditSchemaPort {
    // existing implementation unchanged
}
```

No callers need updating — this is a type-declaration change only, preparing the
contract for the future `PostgresAuditSchemaEnforcer`.

INVARIANT GUARDS:
- §5.23: `AuditSchemaPort` must live in arcclaw-persistence-api (no DDL logic there,
  only the interface). SQLite DDL stays in arcclaw-persistence-sqlite. No DDL in core.
- T3-4 is a PRE-WORK item only. The actual Postgres WORM DDL is deferred until
  `arcclaw-persistence-postgres` is created. Do NOT create that module here.
- No production behavior changes — the comment and interface declaration are inert.

DONE WHEN:
[ ] `AuditSchemaPort` interface in arcclaw-persistence-api with `enforceWormConstraints()`.
[ ] `AuditSchemaEnforcer implements AuditSchemaPort` in arcclaw-persistence-sqlite.
[ ] Deferred-implementation comment present in `AuditSchemaEnforcer.enforceWormConstraints()`.
[ ] `mvn test -pl arcclaw-persistence-sqlite` passes.
[ ] No new modules created.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
FINAL VALIDATION (run after all 5 items are complete)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Run these checks in order. All must pass before raising a PR.

1. Zero EntityManager in core and api (items 1, 3, 4):
   grep -r "EntityManager" \
     arcclaw-core/src/main \
     arcclaw-persistence-api/src/main
   → Expected: zero results.

2. Full test suite:
   mvn test -pl arcclaw-core,arcclaw-persistence-sqlite,arcclaw-persistence-api
   → Expected: BUILD SUCCESS, zero test failures.

3. Transaction annotation audit — confirm these have NOT changed:
   - AgentCronScheduler.pollAndDispatch          → @Transactional(NOT_SUPPORTED)
   - ApprovalStateMachine.decide                 → @Transactional(NOT_SUPPORTED)
   - CheckpointApprovalService.approveTool       → @Transactional(NOT_SUPPORTED)
   - CheckpointApprovalService.denyTool          → @Transactional(NOT_SUPPORTED)
   - WorkspaceManager.gcSweep                    → @Transactional(NOT_SUPPORTED)
   - AsyncPersistenceEngine (drain threads)      → no @Transactional

4. TemporalUtils.formatIsoLex() usage:
   grep -r "TemporalUtils" arcclaw-core/src arcclaw-persistence-api/src
   → Expected: zero results.

5. CronStorePort write methods:
   grep -A2 "void reclaimStaleCrons\|void claimForFire\|void save\|void deleteById\|void updateNextFireTime" \
     arcclaw-persistence-sqlite/src/main/java/com/arcclaw/persistence/sqlite/SqliteCronStoreAdapter.java
   → Each must have @Transactional(REQUIRES_NEW) immediately above it.