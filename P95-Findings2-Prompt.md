You are performing a precision surgical audit of a storage-backend extraction refactor in a Quarkus multi-module project (ArcClaw). The original working code is in branch `main` and a git diff introducing the new adapter layer is provided. Your task is to fix confirmed bugs, verify behavioral equivalence, and complete missing implementations. Do NOT make assumptions — verify every change against the original.

=== MODULE CONTEXT ===
- arcclaw-persistence-api: Port interfaces + DTOs (no JPA, no SQLite)
- arcclaw-persistence-sqlite: SQLite adapter implementations (CheckpointStorePort, CompactionStorePort, MemoryRetrievalPort, PersistenceAdapterPort)
- arcclaw-core: Domain logic, delegates to port interfaces via @Inject
- InstantToTextConverter is in arcclaw-persistence-sqlite with autoApply=true

=== BUG 1: CRITICAL — reclaimStaleCrons Missing Parentheses ===
File: arcclaw-core/src/main/java/com/arcclaw/core/state/AgentCronRepository.java
Method: reclaimStaleCrons(Instant threshold)

The new JPQL:
  "WHERE c.status = :running AND c.lastFireTime <= :threshold OR c.lastFireTime IS NULL AND c.createdAt <= :threshold"

Is WRONG. AND binds before OR, so non-RUNNING crons with null lastFireTime get reset to ACTIVE.

Fix: Add parentheses to match original SQL semantics:
  "WHERE c.status = :running AND (c.lastFireTime <= :threshold OR (c.lastFireTime IS NULL AND c.createdAt <= :threshold))"

=== BUG 2: CRITICAL — Verify A16 Stale Join Guard in SqliteCheckpointStoreAdapter.save() ===
File: arcclaw-persistence-sqlite/.../SqliteCheckpointStoreAdapter.java
Method: save(CheckpointDto dto)

Original StatefulAgentStoreImpl.doSaveEntity() had this guard (comment: "A16 Stale Join Guard"):
  if (checkpoint.getStatus() == AWAITING_APPROVAL && managed.getStatus() == AWAITING_APPROVAL) {
      mergeBatchDecisions(managed, checkpoint);  // merges per-tool decisions, never overwrites
  } else {
      managed.setBatchToolDecisions(checkpoint.getBatchToolDecisions());
  }

Verify that SqliteCheckpointStoreAdapter.save() replicates this guard exactly. If missing, add it. 
This protects concurrent parallel batch HITL flows from overwriting each other's tool decisions.
mergeBatchDecisions merges the batchToolDecisions JSON: for each tool in the incoming map, 
only update if the managed value is PENDING (never downgrade a APPROVED/DENIED decision).

=== BUG 3: CRITICAL — Verify FTS5 Sync in SqliteCompactionStoreAdapter.upsert() ===
File: arcclaw-persistence-sqlite/.../SqliteCompactionStoreAdapter.java
Method: upsert(CompactionSummaryDto dto)

Original CompactionSummaryRepository.upsertSummary() ran TWO isolated transactions:
  1. doUpsertSummary() [@Transactional(REQUIRES_NEW)] — persist CompactionSummaryEntity
  2. syncFtsBestEffort() [@Transactional(REQUIRES_NEW)] — DELETE + INSERT into fts.compactionsummaryfts

The FTS sync pattern was:
  em.createNativeQuery("DELETE FROM fts.compactionsummaryfts WHERE sessionId = ?1").setParameter(1, sessionId).executeUpdate();
  em.createNativeQuery("INSERT INTO fts.compactionsummaryfts(sessionId, summary) VALUES(?1, ?2)").setParameter(1, sessionId).setParameter(2, summaryText).executeUpdate();

Verify SqliteCompactionStoreAdapter.upsert() includes this FTS sync in a separate REQUIRES_NEW transaction, with the same catch-and-log-but-don't-fail error handling. If missing, add it following the same isolation pattern.

=== BUG 4: HIGH — Restore SQLite BUSY Retry on Startup Zombie Recovery ===
File: arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/SessionReaper.java
Method: onStartup()

Original code:
  retryTemplate.executeVoid("onStartup-zombies", 5, 50,
      () -> QuarkusTransaction.requiringNew().run(this::doRecoverZombies));

New code just calls QuarkusTransaction.requiringNew().run(this::doRecoverZombies) with no retry.
If SQLite WAL is being checkpointed at boot, this single attempt fails silently, leaving INPROGRESS zombies permanently.

Fix: Call checkpointStore.recoverZombies(cutoff) OR wrap the QuarkusTransaction call in the SqliteRetryTemplate directly (inject SqliteRetryTemplate and wrap). The retry belongs at this call site because this is startup-critical path. If CheckpointStorePort already has retry inside, document the invariant explicitly that a single call is retry-safe.

=== ISSUE 5: MEDIUM — CronStatus Parameter Binding Inconsistency in UPDATE Queries ===
File: arcclaw-core/src/main/java/com/arcclaw/core/state/AgentCronRepository.java
Methods: claimForFire, releaseClaimToActive, markFired, markCompleted, markAwaitingApproval, markFailed, markSkipped, reclaimStaleCrons

These UPDATE queries bind CronStatus parameters as .name() (String):
  .setParameter("running", CronStatus.RUNNING.name())

While findDueCrons correctly passes the enum value:
  .setParameter("status", CronStatus.ACTIVE)  // enum, converter applies

Action: Change all UPDATE parameters to pass the CronStatus enum directly (not .name()), 
consistent with findDueCrons and findAwaitingApprovalByUserId. This ensures CronStatusConverter 
is applied uniformly. Verify CronStatusConverter.toStorageValue() returns the enum name 
as stored in existing rows.

=== ISSUE 6: MEDIUM — purgeOldChatHistory Instant Parameter Binding Verification ===
File: arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/SessionReaper.java
Method: purgeOldChatHistory()

The bulk DELETE JPQL: "... WHERE c.updatedAt < :cutoff ..."
.setParameter("cutoff", cutoff) where cutoff is now Instant.

AgentCheckpoint.updatedAt is now Instant with InstantToTextConverter(autoApply=true).
Verify that Hibernate 6 applies the converter for bulk DELETE JPQL parameters 
of autoApply converters (known edge case in ORM 6).

If it does NOT apply: change the parameter to use InstantToTextConverter.format(cutoff) 
and bind as a String: .setParameter("cutoff", InstantToTextConverter.format(cutoff), String.class).
Add a comment documenting the choice.

=== ISSUE 7: LOW — Verify autoApply Works Cross-Module for 3 Entities ===
Files:
  arcclaw-app/.../session/SessionRegistryEntry.java (createdAt: Instant, @Convert removed)
  arcclaw-core/.../state/RagChunkEntity.java (createdAt: Instant, @Convert removed)
  arcclaw-core/.../skill/AgentSkillEntity.java (createdAt, updatedAt: Instant, @Convert removed)

All three had explicit @Convert(converter=InstantToTextConverter.class) removed, 
relying on autoApply=true from arcclaw-persistence-sqlite module.

Action:
1. Write a Quarkus @QuarkusTest integration test that persists and reads back each of these 
   three entity types, asserting the Instant field round-trips correctly through TEXT storage.
2. If any test fails (raw epoch format stored instead of ISO 8601), restore the explicit 
   @Convert annotation on that entity. The autoApply is correct architecturally but the test 
   confirms runtime behavior.

=== GENERAL VERIFICATION CHECKLIST ===
For each of the above fixes, ensure:
- All @Transactional annotations are correct (REQUIRES_NEW where isolation is needed)
- No EntityManager is injected in arcclaw-core classes that were refactored to use ports
- All new DTO → entity conversions are null-safe
- Error logs retain the original level (WARN vs ERROR) and include sessionId
- No test class references PersistenceRetryEngine.sleepQuietly() (it's deleted)

---
