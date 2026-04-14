You are fixing a partially-complete storage backend extraction diff on the
ArcClaw codebase (Quarkus 3.23+, Java 21, JPA/Hibernate, SQLite adapter).
The code compiles but has multiple runtime regressions introduced during the
migration.  Fix EVERY issue below in the exact files listed.
Do NOT change any logic, behaviour, or contracts that are not listed here.
Do NOT add new abstractions.  Do NOT refactor beyond what is described.

════════════════════════════════════════════════════════════
 CRITICAL — Will break at runtime immediately
════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────
C1.  FILE: arcclaw-persistence-sqlite/.../SqliteCheckpointStoreAdapter.java
     METHOD: doFindBySessionAndRole(String sessionId, String agentRole)
──────────────────────────────────────────────────────────
CURRENT (wrong):
    "SELECT c FROM AgentCheckpoint c
     WHERE c.sessionId = :sid AND c.agentRole = :role"
    // No ORDER BY, no ARCHIVED exclusion, no setMaxResults

REQUIRED (exact original guards must be restored):
    "SELECT c FROM AgentCheckpoint c
     WHERE c.sessionId = :sid
       AND c.agentRole = :role
       AND c.status <> :archived
     ORDER BY c.id DESC"
    .setParameter("archived", CheckpointStatus.ARCHIVED)
    .setMaxResults(1)

REASON: Without ORDER BY DESC the row returned is non-deterministic
(usually oldest).  Without ARCHIVED exclusion, AgentLoop's
archive-then-create pattern returns the just-archived row instead of
the new live checkpoint, breaking every subsequent load/save/approve call.

──────────────────────────────────────────────────────────
C2.  FILE: arcclaw-core/.../StatefulAgentStoreImpl.java
     METHOD: findLatestAwaitingApproval(String sessionId)
──────────────────────────────────────────────────────────
CURRENT (wrong):
    return storeAdapter.findActiveBySession(sessionId).stream()
            .filter(d -> CheckpointStatus.AWAITING_APPROVAL.toString().equals(d.status()))
            .map(this::dtoToEntity)
            .findFirst();

findActiveBySession() uses ORDER BY c.id ASC. After filtering,
findFirst() returns the OLDEST AWAITING_APPROVAL row. The original
explicitly used ORDER BY c.id DESC to prevent stale-row ambiguity
where /approve returns "No pending tool executions require approval".

REQUIRED FIX:
  1. Add a dedicated port method to CheckpointStorePort:
       Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId);

  2. Implement it in SqliteCheckpointStoreAdapter:
       public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
           return withRetry(() -> doFindLatestAwaitingApproval(sessionId),
                            "findLatestAwaitingApproval");
       }
       @Transactional(TxType.REQUIRES_NEW)
       public Optional<CheckpointDto> doFindLatestAwaitingApproval(String sessionId) {
           em.clear();
           return em.createQuery(
                   "SELECT c FROM AgentCheckpoint c " +
                   "WHERE c.sessionId = :sid AND c.status = :status " +
                   "ORDER BY c.id DESC",
                   AgentCheckpoint.class)
               .setParameter("sid", sessionId)
               .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
               .setMaxResults(1)
               .getResultList().stream()
               .filter(Objects::nonNull)
               .map(this::entityToDto)
               .findFirst();
       }

  3. In StatefulAgentStoreImpl.findLatestAwaitingApproval():
       return storeAdapter.findLatestAwaitingApproval(sessionId)
               .map(this::dtoToEntity);

──────────────────────────────────────────────────────────
C3.  FILE: arcclaw-core/.../StatefulAgentStoreImpl.java
     METHOD: findLatestRole(String sessionId)
──────────────────────────────────────────────────────────
CURRENT (semantic inversion — wrong):
    return storeAdapter.findLatestBySessionAndStatus(sessionId,
               CheckpointStatus.ARCHIVED.name())
            .map(CheckpointDto::agentRole);
  // Finds ARCHIVED rows — completely backwards

REQUIRED (find latest NON-archived row, return its role):
  Option A — use the existing findLatestBySession port method if it
  already excludes ARCHIVED:
    return storeAdapter.findLatestBySession(sessionId)
            .map(CheckpointDto::agentRole);

  Option B — if findLatestBySession does not exclude ARCHIVED, add:
       Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId)
  in the port, implemented as:
       "SELECT c FROM AgentCheckpoint c
        WHERE c.sessionId = :sid AND c.status <> :archived
        ORDER BY c.id DESC"
       setMaxResults(1)
  and delegate to it.

  The original JPQL was:
    "SELECT c.agentRole FROM AgentCheckpoint c
     WHERE c.sessionId = :sid AND c.status <> :archived
     ORDER BY c.id DESC"
  NEVER return the role of an ARCHIVED row.

──────────────────────────────────────────────────────────
C4.  FILE: arcclaw-persistence-sqlite/.../SqliteCheckpointStoreAdapter.java
     METHOD: doIsCheckpointExpired(String sessionId, long timeoutHours)
──────────────────────────────────────────────────────────
CURRENT (wrong — counts ALL old checkpoints, not AWAITING_APPROVAL):
    Long count = em.createQuery(
        "SELECT COUNT(c) FROM AgentCheckpoint c
         WHERE c.sessionId = :sid AND c.updatedAt < :cutoff", Long.class)
        ...
    return count > 0;
  // Returns true for any session with any old completed/archived row.
  // Breaks ALL HITL approval windows.

REQUIRED FIX (restore original logic exactly):
    @Transactional(TxType.REQUIRES_NEW)
    public boolean doIsCheckpointExpired(String sessionId, long timeoutHours) {
        Instant cutoff = Instant.now().minus(timeoutHours, ChronoUnit.HOURS);
        em.clear();
        List<AgentCheckpoint> rows = em.createQuery(
                "SELECT c FROM AgentCheckpoint c " +
                "WHERE c.sessionId = :sid AND c.status = :status " +
                "ORDER BY c.id DESC",
                AgentCheckpoint.class)
            .setParameter("sid", sessionId)
            .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
            .setMaxResults(1)
            .getResultList();
        if (rows.isEmpty()) return true;   // no AWAITING_APPROVAL → treat as expired
        AgentCheckpoint cp = rows.get(0);
        if (cp.getUpdatedAt() == null) return true;
        return cp.getUpdatedAt().isBefore(cutoff);
    }

──────────────────────────────────────────────────────────
C5a. FILE: arcclaw-persistence-sqlite/.../SqliteWalConfigurer.java
     Missing PRAGMA busy_timeout
──────────────────────────────────────────────────────────
CURRENT (missing):
    stmt.execute("PRAGMA journal_mode=WAL;");
    stmt.execute("PRAGMA synchronous=NORMAL;");
    // busy_timeout is absent

REQUIRED:
    stmt.execute("PRAGMA journal_mode=WAL;");
    stmt.execute("PRAGMA synchronous=NORMAL;");
    stmt.execute("PRAGMA busy_timeout=10000;");   // ← add back

Without this, SQLite returns BUSY immediately on any lock contention
instead of waiting up to 10 seconds.  The retry template exists to
handle BUSY, but without a timeout the contention window is far wider.

──────────────────────────────────────────────────────────
C5b. FILE: arcclaw-persistence-sqlite/.../SqliteWalConfigurer.java
     Wrong MicroProfile Config property name for data dir
──────────────────────────────────────────────────────────
CURRENT (wrong):
    @ConfigProperty(name = "ARCCLAW_DATA_DIR", defaultValue = ".")
    String dataDir;

REQUIRED:
    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = ".")
    String dataDir;

Quarkus auto-maps arcclaw.data-dir ↔ ARCCLAW_DATA_DIR via env var
translation.  Using the raw env var name as config key bypasses that
mapping; the bean always resolves to "." (CWD), placing arcclaw-fts.db
in the wrong directory on every startup.

════════════════════════════════════════════════════════════
 HIGH — Semantic regressions or significant data integrity risk
════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────
H1.  FILE: arcclaw-core/.../StatefulAgentStoreImpl.java
     METHOD: saveAndFinalize(...)
──────────────────────────────────────────────────────────
CURRENT (two separate committed transactions — non-atomic):
    AgentCheckpoint checkpoint = save(sessionId, agentRole, steps); // COMMIT #1: IN_PROGRESS
    checkpoint.setStatus(finalStatus);
    checkpoint.setCompletedAt / setFailedAt(...);
    CheckpointDto saved = storeAdapter.saveCheckpointWithSteps(      // COMMIT #2: COMPLETED/FAILED
        entityToDto(checkpoint), ...);

JVM crash between commit #1 and #2 leaves checkpoint stuck at
IN_PROGRESS permanently.  SessionReaper's zombie GC won't fire for
15 minutes.  The original was a single REQUIRES_NEW transaction.

REQUIRED FIX — eliminate the first save(), build state in-memory:
    public AgentCheckpoint saveAndFinalize(String sessionId, String agentRole,
            List<ReasoningStep> steps, CheckpointStatus finalStatus) {
        String json = serializeSteps(steps);
        AgentCheckpoint checkpoint = load(sessionId, agentRole)
                .orElseGet(AgentCheckpoint::new);
        checkpoint.setSessionId(sessionId);
        checkpoint.setAgentRole(agentRole);
        checkpoint.setStateJson(json);
        checkpoint.setStatus(finalStatus);
        if (finalStatus == CheckpointStatus.COMPLETED)
            checkpoint.setCompletedAt(Instant.now());
        else if (finalStatus == CheckpointStatus.FAILED)
            checkpoint.setFailedAt(Instant.now());
        CheckpointDto saved = storeAdapter.saveCheckpointWithSteps(
                entityToDto(checkpoint),
                steps.stream().map(this::mapToReasoningStepDto).toList());
        // ... audit call unchanged ...
        return dtoToEntity(saved);
    }

──────────────────────────────────────────────────────────
H2.  FILE: arcclaw-core/.../StatefulAgentStoreImpl.java
     METHOD: markOrphaned(Long checkpointId, String reason)
──────────────────────────────────────────────────────────
CURRENT (null passed as audit detail):
    auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
        SecurityEventType.CHECKPOINT_ORPHANED,
        null,             // ← BUG
        idempotencyKey);

REQUIRED:
    auditService.recordWithIdempotency(dto.sessionId(), userId, dto.agentRole(),
        SecurityEventType.CHECKPOINT_ORPHANED,
        reason,           // ← reason is in scope in the lambda
        idempotencyKey);

──────────────────────────────────────────────────────────
H3.  FILE: arcclaw-persistence-sqlite/.../SqliteCheckpointStoreAdapter.java
     METHOD: doFindStaleApprovals(Instant cutoff)
──────────────────────────────────────────────────────────
CURRENT (brittle hardcoded string literal in JPQL):
    "SELECT c FROM AgentCheckpoint c
     WHERE c.status = 'AWAITING_APPROVAL' AND c.updatedAt < :cutoff"

REQUIRED (use enum parameter):
    "SELECT c FROM AgentCheckpoint c
     WHERE c.status = :status AND c.updatedAt < :cutoff"
    .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
    .setParameter("cutoff", cutoff)

──────────────────────────────────────────────────────────
H4.  FILE: arcclaw-persistence-sqlite/.../SqliteMemoryRetrievalAdapter.java
     METHOD: index(MemoryEntryDto entry)
──────────────────────────────────────────────────────────
CURRENT (sessionKb never indexed — session-scoped RAG broken):
    public void index(MemoryEntryDto entry) {
        globalKb.ingest(entry.text());   // ← only global; sessionKb omitted
    }

REQUIRED (restore both-tier ingest from original IngestDocumentTool):
    public void index(MemoryEntryDto entry) {
        String chunkId = globalKb.ingest(entry.text());
        sessionKb.ingest(
            chunkId != null ? chunkId : "session-" + entry.label(),
            entry.text());
    }

After migration, sessionKb.search() inside the adapter's search path
returns nothing for documents ingested in the current session.

──────────────────────────────────────────────────────────
H5.  FILE: arcclaw-core/.../persistence/AsyncPersistenceEngine.java
     HMAC boot-window retry deleted from PersistenceRetryEngine,
     not replaced in new code path
──────────────────────────────────────────────────────────
ORIGINAL PersistenceRetryEngine.persistSynchronously() had:
    catch (IllegalStateException e) {
        if (e.getMessage().contains("HMAC key unavailable") && hmacRetry < 3) {
            Thread.sleep(100);   hmacRetry++;   continue;
        }
        throw e;
    }

PersistenceRetryEngine is now deleted.  The new path:
    persistenceAdapter.execute(sessionId, eventType, dbAction)
has no HMAC retry visible anywhere in the diff.

REQUIRED FIX — add to AsyncPersistenceEngine.processEvent() around
the adapter.execute() call:
    int hmacRetry = 0;
    while (true) {
        try {
            persistenceAdapter.execute(
                event.getSessionId(),
                event.getEventType().toString(),
                event.getDbAction());
            break;
        } catch (IllegalStateException e) {
            if (e.getMessage() != null
                    && e.getMessage().contains("HMAC key unavailable")
                    && hmacRetry++ < 3) {
                sleepQuietly(100);
                continue;
            }
            throw e;
        }
    }

This is a CORE correctness concern (Vault key loaded async at boot),
NOT SQLite-specific.  It belongs in AsyncPersistenceEngine, not the
adapter.

════════════════════════════════════════════════════════════
 MEDIUM — Correctness concerns, race windows, operational impact
════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────
M1.  FILE: arcclaw-persistence-sqlite/.../SqliteWalConfigurer.java
     First-startup ATTACH DATABASE skipped silently
──────────────────────────────────────────────────────────
CURRENT:
    if (ftsDb.exists()) {
        stmt.execute("ATTACH DATABASE ...");
    } else {
        log.warnf("SQLite: FTS database not found at %s. Skipping ATTACH.", ...);
    }

On very first startup arcclaw-fts.db doesn't exist yet; AuditSchemaEnforcer
creates it later.  The warn log is misleading and operators interpret it as
an error.

REQUIRED:
    if (ftsDb.exists()) {
        stmt.execute("ATTACH DATABASE '" + ftsDb.getAbsolutePath() + "' AS fts;");
        log.infof("SQLite: FTS database attached from %s", ftsDb.getAbsolutePath());
    } else {
        log.debugf("SQLite: FTS database not yet present at %s — " +
                   "will be created by AuditSchemaEnforcer on first write.",
                   ftsDb.getAbsolutePath());
    }

──────────────────────────────────────────────────────────
M2.  FILE: arcclaw-core/.../lifecycle/SessionReaper.java
     onStartup retry loop retries on ALL exceptions, not just BUSY
──────────────────────────────────────────────────────────
CURRENT (new manual retry loop retries on any exception type):
    } catch (Exception e) {
        if (attempt >= maxAttempts) { log.error(...); }
        else { log.warn(...); Thread.sleep(100L * attempt + jitter); }
    }

REQUIRED — only retry on SQLite BUSY (same contract as old retryTemplate):
    } catch (Exception e) {
        boolean isBusy = SqliteErrorStrings.isBusy(e);
        if (attempt >= maxAttempts || !isBusy) {
            log.errorf("Failed onStartup zombie recovery after %d attempts: %s",
                maxAttempts, e.getMessage());
            log.debug(e, "Full stacktrace for onStartup zombie recovery failure");
            return;
        }
        log.warnf("Attempt %d: BUSY on zombie recovery, retrying...", attempt);
        Thread.sleep(100L * attempt + jitter);
    }

Schema errors, NPEs, and constraint violations must NOT be retried.

──────────────────────────────────────────────────────────
M3.  FILE: arcclaw-core/.../lifecycle/SessionReaper.java
     doRecoverZombies() uses EntityManager directly — bypasses port
──────────────────────────────────────────────────────────
CURRENT:
    List<AgentCheckpoint> inProgressZombies = em.createQuery(
        "SELECT c FROM AgentCheckpoint c WHERE c.status = :status AND c.updatedAt < :cutoff",
        AgentCheckpoint.class)...

CheckpointStorePort is already injected in SessionReaper but not used
for queries.  WorkspaceManager was correctly migrated.

REQUIRED:
  1. Add to CheckpointStorePort interface:
       List<CheckpointDto> findInProgressZombies(Instant cutoff);
       List<CheckpointDto> findStaleAwaitingApproval(Instant cutoff);

  2. Implement both in SqliteCheckpointStoreAdapter using existing JPQL
     (no logic change — just move the query behind the port boundary).

  3. In SessionReaper.doRecoverZombies(), replace em.createQuery calls
     with checkpointStore.findInProgressZombies(cutoff) and
     checkpointStore.findStaleAwaitingApproval(cutoff).
     Map CheckpointDto → AgentCheckpoint via dtoToEntity where needed
     (or pass DTO fields directly to the existing per-row handlers).

──────────────────────────────────────────────────────────
M4.  FILE: arcclaw-persistence-sqlite/.../SqliteMemoryRetrievalAdapter.java
     sessionKb.search() swallows all exceptions silently
──────────────────────────────────────────────────────────
CURRENT:
    try {
        sessionKb.search(query, topK * 2).forEach(...);
    } catch (Exception e) {
        // Session scope might not be active in some contexts
    }

This hides real exceptions (index corruption, OOM, etc.) behind a
"scope not active" comment.

REQUIRED — use the explicit scope guard from original TwoTierMemoryService:
    if (WorkspaceScopeHolder.isActive()) {
        sessionKb.search(query, topK * 2).forEach(...);
    }
    // No try/catch needed — scope is checked explicitly

──────────────────────────────────────────────────────────
M5.  FILE: arcclaw-core/.../StatefulAgentStoreImpl.java
     METHOD: load(String sessionId, String agentRole)
     Transaction semantics changed from REQUIRED to REQUIRES_NEW
──────────────────────────────────────────────────────────
ORIGINAL: @Transactional (REQUIRED) — joined the caller's transaction.
NEW: delegates to adapter doFindBySessionAndRole (REQUIRES_NEW) —
     always suspends caller's transaction.

Callers that persist then immediately load in the same REQUIRED tx
(e.g. some paths in CheckpointApprovalService) will see stale state
(read-your-own-writes broken).

REQUIRED — restore SUPPORTS on the outer method to honour caller context:
    @Override
    @Transactional(TxType.SUPPORTS)
    public Optional<AgentCheckpoint> load(String sessionId, String agentRole) {
        return storeAdapter.findBySessionAndRole(sessionId, agentRole)
                .map(this::dtoToEntity);
    }

The adapter's doFindBySessionAndRole will still use REQUIRES_NEW when
called without an ambient transaction (which is the common path).

──────────────────────────────────────────────────────────
M6.  DRAIN THREAD — TELEMETRY exhaustion logs at ERROR instead of WARN
──────────────────────────────────────────────────────────
FILE: arcclaw-core/.../persistence/AsyncPersistenceEngine.java
      drainTelemetry() catch block

CURRENT path:
  SqlitePersistenceAdapter.execute() throws RuntimeException on exhaustion
  → processEvent() wraps in ComplianceViolationException
  → drainTelemetry() outer catch logs log.errorf(...)

REQUIRED — telemetry exhaustion is acceptable data loss (fire-and-forget tier);
it must NOT produce ERROR-level log noise:

In drainTelemetry()'s outer catch:
    } catch (Exception e) {
        if (event.getTier() == PersistenceEvent.Tier.TELEMETRY) {
            log.warnf("TELEMETRY event dropped after retry exhaustion " +
                      "for session %s type %s: %s",
                      event.getSessionId(), event.getEventType(), e.getMessage());
        } else {
            log.errorf(...);  // CRITICAL tier keeps ERROR
        }
    }

════════════════════════════════════════════════════════════
 LOW — Cleanup / operational polish
════════════════════════════════════════════════════════════

L1. Restore log.infof("Checkpoint %d marked COMPLETED …") in
    markCompleted() — was present in original doMarkCompletedId().

L2. Restore log.warnf("Checkpoint %s/%s marked ORPHANED: %s", …) in
    markOrphaned() lambda — was present in original doMarkOrphaned().

L3. FILE: arcclaw-core/.../StatefulAgentStoreImpl.java
    updateBatchToolDecision() — restore @Transactional(NOT_SUPPORTED)
    annotation.  The only current caller (ApprovalStateMachine.decide())
    is already NOT_SUPPORTED so there is no immediate regression.
    However, the annotation is a documented contract protecting future
    callers from accidentally running this inside a long REQUIRED
    transaction (§5.10.1 hazard).

════════════════════════════════════════════════════════════
 ARCHITECTURAL INVARIANTS — Do NOT violate these while fixing
════════════════════════════════════════════════════════════

• §5.10.1  ApprovalStateMachine.decide() MUST keep @Transactional(NOT_SUPPORTED).
           Do NOT move this annotation into the adapter layer.

• §5.14.1  SqliteRetryTemplate MUST remain inside arcclaw-persistence-sqlite.
           Do NOT expose PersistenceRetryPort or any retry abstraction to core.

• §5.21    InstantToTextConverter @Converter(autoApply=true) MUST remain
           SQLite-only (inside arcclaw-persistence-sqlite).
           Do NOT add any competing autoApply Instant converter for Postgres.
           The DM1 migration above must run before any entity reads on
           upgraded SQLite databases.

• CheckpointStorePort (arcclaw-persistence-api) MUST contain only:
    - CheckpointStorePort    (checkpoint CRUD + status transitions)
    - MemoryRetrievalPort    (search + index)
    - BackendCapabilities    (feature flags)
  Do NOT add PersistenceRetryPort or any SQLite-specific type to the API module.

════════════════════════════════════════════════════════════
 VERIFIED CORRECT — Do NOT change these
════════════════════════════════════════════════════════════
• ApprovalStateMachine.decide() @Transactional(NOT_SUPPORTED) — correct ✓
• WorkspaceManager.markCheckpointsFailed → checkpointStore.markCheckpointsFailed() ✓
• SessionRegistryEntry @Convert(InstantToTextConverter) annotation removed ✓
• arcclaw-core/pom.xml: quarkus-jdbc-sqlite removed, arcclaw-persistence-api added ✓
• arcclaw-app/pom.xml: arcclaw-persistence-sqlite added as runtime dep ✓
• ArcClawSQLiteDialect path updated in application.yaml ✓
• CoreReflectionConfig: InstantToTextConverter + ArcClawSQLiteDialect removed ✓
• findLatestAwaitingApprovalForCron → findLatestAwaitingApprovalWithPrefix (ORDER BY DESC ✓)
• updateBatchToolDecision Stale-Join Guard (A16) logic preserved ✓
• PersistenceEvent.dbAction Consumer<EntityManager> → Runnable ✓
• CheckpointMapper in arcclaw-core (pure mapping, no DB concern) ✓
• AgentCronRepository status literal → enum parameter ✓
• SessionReaper LocalDateTime/ZoneOffset casts removed (uses Instant directly) ✓