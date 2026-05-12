package com.arcclaw.persistence.api;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SR-3: Contract tests for persistence port interfaces.
 * Uses lightweight in-memory mock adapters to validate interface contracts.
 */
class PersistencePortContractTest {

    private InMemoryCheckpointStore checkpointStore;
    private InMemoryCronStore cronStore;
    private InMemoryAuditEventStore auditStore;
    private InMemoryAdvisoryLock advisoryLock;

    @BeforeEach
    void setUp() {
        checkpointStore = new InMemoryCheckpointStore();
        cronStore = new InMemoryCronStore();
        auditStore = new InMemoryAuditEventStore();
        advisoryLock = new InMemoryAdvisoryLock();
    }

    // ── CheckpointStorePort contract ──

    @Test
    void checkpointSaveAndFindById() {
        CheckpointDto dto = createCheckpoint("sess-1", "IN_PROGRESS");
        CheckpointDto saved = checkpointStore.save(dto);
        assertNotNull(saved.id());
        Optional<CheckpointDto> found = checkpointStore.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("sess-1", found.get().sessionId());
    }

    @Test
    void checkpointTransitionStatus() {
        CheckpointDto saved = checkpointStore.save(createCheckpoint("sess-2", "IN_PROGRESS"));
        checkpointStore.transitionStatus(saved.id(), "COMPLETED");
        Optional<CheckpointDto> found = checkpointStore.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("COMPLETED", found.get().status());
    }

    @Test
    void checkpointAtomicTransitionSuccess() {
        CheckpointDto saved = checkpointStore.save(createCheckpoint("sess-3", "AWAITING_APPROVAL"));
        boolean result = checkpointStore.atomicTransition(saved.id(), "AWAITING_APPROVAL", "APPROVED");
        assertTrue(result);
        assertEquals("APPROVED", checkpointStore.findById(saved.id()).get().status());
    }

    @Test
    void checkpointAtomicTransitionFailsOnWrongStatus() {
        CheckpointDto saved = checkpointStore.save(createCheckpoint("sess-4", "COMPLETED"));
        boolean result = checkpointStore.atomicTransition(saved.id(), "AWAITING_APPROVAL", "APPROVED");
        assertFalse(result);
    }

    @Test
    void checkpointWithSteps() {
        CheckpointDto saved = checkpointStore.save(createCheckpoint("sess-5", "IN_PROGRESS"));
        List<ReasoningStepDto> steps = List.of(
            new ReasoningStepDto("thought", "action", "{}", "obs", 100, 50, true, null, null, null)
        );
        CheckpointDto result = checkpointStore.saveCheckpointWithSteps(saved, steps);
        List<ReasoningStepDto> loaded = checkpointStore.loadSteps(result.id());
        assertEquals(1, loaded.size());
        assertEquals("thought", loaded.get(0).thoughtJson());
    }

    @Test
    void checkpointFindActiveBySession() {
        checkpointStore.save(createCheckpoint("sess-6", "IN_PROGRESS"));
        checkpointStore.save(createCheckpointWithId("sess-6", "COMPLETED"));
        checkpointStore.save(createCheckpointWithId("sess-6", "IN_PROGRESS"));
        List<CheckpointDto> active = checkpointStore.findActiveBySession("sess-6");
        assertEquals(2, active.size());
    }

    // ── CronStorePort contract ──

    @Test
    void cronSaveAndFindById() {
        AgentCronDto cron = createCron("cron-1", "user-1", "ACTIVE");
        cronStore.save(cron);
        Optional<AgentCronDto> found = cronStore.findById("cron-1");
        assertTrue(found.isPresent());
        assertEquals("user-1", found.get().userId());
    }

    @Test
    void cronClaimForFireSuccess() {
        AgentCronDto cron = createCron("cron-2", "user-1", "ACTIVE");
        cronStore.save(cron);
        assertTrue(cronStore.claimForFire("cron-2"));
    }

    @Test
    void cronClaimForFireFailsOnWrongStatus() {
        AgentCronDto cron = createCron("cron-3", "user-1", "COMPLETED");
        cronStore.save(cron);
        assertFalse(cronStore.claimForFire("cron-3"));
    }

    @Test
    void cronFindDueCrons() {
        AgentCronDto due = new AgentCronDto(
            "cron-due", "sess-1", "user-1", "chat-1", "telegram",
            "plat-1", "0 0 * * * ?", "UTC", false, "goal",
            Instant.now().minusSeconds(60), Instant.now().minusSeconds(120),
            "ACTIVE", "assistant", null, null, Instant.now(), Instant.now()
        );
        AgentCronDto notDue = new AgentCronDto(
            "cron-future", "sess-2", "user-1", "chat-1", "telegram",
            "plat-1", "0 0 * * * ?", "UTC", false, "goal",
            Instant.now().plusSeconds(3600), null,
            "ACTIVE", "assistant", null, null, Instant.now(), Instant.now()
        );
        cronStore.save(due);
        cronStore.save(notDue);
        List<AgentCronDto> dueCrons = cronStore.findDueCrons();
        assertEquals(1, dueCrons.size());
        assertEquals("cron-due", dueCrons.get(0).id());
    }

    @Test
    void cronLifecycleTransitions() {
        AgentCronDto cron = createCron("cron-lifecycle", "user-1", "ACTIVE");
        cronStore.save(cron);

        assertTrue(cronStore.claimForFire("cron-lifecycle"));
        cronStore.markFired("cron-lifecycle", Instant.now(), Instant.now().plusSeconds(3600));
        Optional<AgentCronDto> afterFire = cronStore.findById("cron-lifecycle");
        assertTrue(afterFire.isPresent());
        assertEquals("ACTIVE", afterFire.get().status());

        cronStore.save(createCron("cron-complete", "user-1", "ACTIVE"));
        assertTrue(cronStore.claimForFire("cron-complete"));
        cronStore.markCompleted("cron-complete", Instant.now());
        assertEquals("COMPLETED", cronStore.findById("cron-complete").get().status());
    }

    // ── AuditEventStorePort contract ──

    @Test
    void auditSaveAndFindBySession() {
        AuditEventDto event = new AuditEventDto(
            UUID.randomUUID(), "sess-10", "user-1", "assistant", "goal-hash",
            "SUCCESS", "TOOL_CALL", "{}", 100, 50, BigDecimal.ZERO,
            Instant.now(), 1, "1.0", "key-1", 1L, "hash-1", null
        );
        auditStore.save(event);
        List<AuditEventDto> events = auditStore.findBySessionId("sess-10");
        assertEquals(1, events.size());
    }

    @Test
    void auditFindLatestHash() {
        auditStore.save(createAuditEvent("sess-11", 1L, "hash-a", "key-a"));
        auditStore.save(createAuditEvent("sess-11", 2L, "hash-b", "key-b"));
        Optional<String> latest = auditStore.findLatestHash("sess-11");
        assertTrue(latest.isPresent());
        assertEquals("hash-b", latest.get());
    }

    @Test
    void auditCountByIdempotencyKey() {
        auditStore.save(new AuditEventDto(
            UUID.randomUUID(), "sess-12", "user-1", "assistant", "gh",
            "SUCCESS", "TOOL_CALL", "{}", 100, 50, BigDecimal.ZERO,
            Instant.now(), 1, "1.0", "key-1", 1L, "h1", null
        ));
        auditStore.save(new AuditEventDto(
            UUID.randomUUID(), "sess-12", "user-1", "assistant", "gh",
            "SUCCESS", "TOOL_CALL", "{}", 100, 50, BigDecimal.ZERO,
            Instant.now(), 1, "1.0", "key-2", 2L, "h2", null
        ));
        assertEquals(1, auditStore.countByIdempotencyKey("key-1"));
        assertEquals(0, auditStore.countByIdempotencyKey("key-none"));
    }

    @Test
    void auditGetMaxSequence() {
        auditStore.save(createAuditEvent("sess-13", 1L, "h1", "k1"));
        auditStore.save(createAuditEvent("sess-13", 5L, "h2", "k2"));
        auditStore.save(createAuditEvent("sess-13", 3L, "h3", "k3"));
        assertEquals(5L, auditStore.getMaxSequence("sess-13"));
        assertEquals(0L, auditStore.getMaxSequence("sess-empty"));
    }

    @Test
    void auditCountEventsByType() {
        auditStore.save(new AuditEventDto(
            UUID.randomUUID(), "sess-14", "user-1", "assistant", "gh",
            "SUCCESS", "TOOL_CALL", "{}", 100, 50, BigDecimal.ZERO,
            Instant.now(), 1, "1.0", "k1", 1L, "h1", null
        ));
        auditStore.save(new AuditEventDto(
            UUID.randomUUID(), "sess-14", "user-1", "assistant", "gh",
            "SUCCESS", "MESSAGE", "{}", 100, 50, BigDecimal.ZERO,
            Instant.now(), 1, "1.0", "k2", 2L, "h2", null
        ));
        assertEquals(1L, auditStore.countEventsByType("sess-14", "TOOL_CALL"));
        assertEquals(0L, auditStore.countEventsByType("sess-14", "UNKNOWN"));
    }

    // ── AdvisoryLockPort contract ──

    @Test
    void advisoryLockTryLockSuccess() {
        assertTrue(advisoryLock.tryLock("lock-1", 30));
        assertTrue(advisoryLock.isLocked("lock-1"));
    }

    @Test
    void advisoryLockTryLockFailsOnReentry() {
        assertTrue(advisoryLock.tryLock("lock-2", 30));
        assertFalse(advisoryLock.tryLock("lock-2", 30));
    }

    @Test
    void advisoryLockUnlock() {
        assertTrue(advisoryLock.tryLock("lock-3", 30));
        advisoryLock.unlock("lock-3");
        assertFalse(advisoryLock.isLocked("lock-3"));
        assertTrue(advisoryLock.tryLock("lock-3", 30));
    }

    @Test
    void advisoryLockIsLockedOnUnlockedKey() {
        assertFalse(advisoryLock.isLocked("nonexistent"));
    }

    // ── Helper factory methods ──

    private CheckpointDto createCheckpoint(String sessionId, String status) {
        return new CheckpointDto(
            null, sessionId, "role", status, "FULL", "{}", "plat-1", "telegram", "goal",
            null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null,
            Instant.now(), Instant.now()
        );
    }

    private CheckpointDto createCheckpointWithId(String sessionId, String status) {
        return new CheckpointDto(
            null, sessionId, "role", status, "FULL", "{}", "plat-1", "telegram", "goal",
            null, null, null, null, null, null, null, null, 0, null, null, null, null, null, null,
            Instant.now(), Instant.now()
        );
    }

    private AgentCronDto createCron(String id, String userId, String status) {
        return new AgentCronDto(
            id, "sess-" + id, userId, "chat-1", "telegram",
            "plat-1", "0 0 * * * ?", "UTC", false, "goal-" + id,
            Instant.now().plusSeconds(3600), null,
            status, "assistant", null, null, Instant.now(), Instant.now()
        );
    }

    private AuditEventDto createAuditEvent(String sessionId, Long seq, String hash, String key) {
        return new AuditEventDto(
            UUID.randomUUID(), sessionId, "user-1", "assistant", "gh",
            "SUCCESS", "EVENT", "{}", 100, 50, BigDecimal.ZERO,
            Instant.now(), 1, "1.0", key, seq, hash, null
        );
    }

    // ── In-memory mock implementations ──

    static class InMemoryCheckpointStore implements CheckpointStorePort {
        final Map<Long, CheckpointDto> store = new ConcurrentHashMap<>();
        final Map<Long, List<ReasoningStepDto>> stepsStore = new ConcurrentHashMap<>();
        final AtomicLong idSeq = new AtomicLong(1);

        @Override
        public CheckpointDto save(CheckpointDto dto) {
            if (dto.id() == null) {
                dto = new CheckpointDto(idSeq.getAndIncrement(), dto.sessionId(), dto.agentRole(),
                    dto.status(), dto.autonomyLevel(), dto.stateJson(), dto.channelPlatformId(),
                    dto.channelType(), dto.originalGoal(), dto.failedAt(), dto.completedAt(),
                    dto.failReason(), dto.pendingToolName(), dto.batchToolDecisions(),
                    dto.pendingToolParametersJson(), dto.preExecutedResults(), dto.maxIterations(),
                    dto.iterationsUsed(), dto.retryAfterMs(), dto.systemNote(), dto.contextDataJson(),
                    dto.errorCode(), dto.traceId(), dto.rootCauseClass(), dto.createdAt(), Instant.now());
            }
            store.put(dto.id(), dto);
            return dto;
        }

        @Override public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
            CheckpointDto saved = save(checkpoint);
            saveSteps(saved.id(), steps);
            return saved;
        }
        @Override public Optional<CheckpointDto> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        @Override public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) { return Optional.empty(); }
        @Override public List<CheckpointDto> findActiveBySession(String sessionId) {
            return store.values().stream()
                .filter(c -> c.sessionId().equals(sessionId) && !"COMPLETED".equals(c.status()) && !"FAILED".equals(c.status()))
                .collect(Collectors.toList());
        }
        @Override public void transitionStatus(Long id, String newStatus) {
            store.computeIfPresent(id, (k, v) -> new CheckpointDto(v.id(), v.sessionId(), v.agentRole(),
                newStatus, v.autonomyLevel(), v.stateJson(), v.channelPlatformId(), v.channelType(),
                v.originalGoal(), v.failedAt(), v.completedAt(), v.failReason(), v.pendingToolName(),
                v.batchToolDecisions(), v.pendingToolParametersJson(), v.preExecutedResults(),
                v.maxIterations(), v.iterationsUsed(), v.retryAfterMs(), v.systemNote(), v.contextDataJson(),
                v.errorCode(), v.traceId(), v.rootCauseClass(), v.createdAt(), Instant.now()));
        }
        @Override public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
            CheckpointDto existing = store.get(id);
            if (existing != null && existing.status().equals(expectedStatus)) {
                transitionStatus(id, newStatus);
                return true;
            }
            return false;
        }
        @Override public boolean claimForResume(String sessionId, String agentRole) { return false; }
        @Override public List<CheckpointDto> findStaleApprovals(Instant cutoff) { return List.of(); }
        @Override public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) { return Optional.empty(); }
        @Override public Optional<CheckpointDto> findLatestBySession(String sessionId) { return Optional.empty(); }
        @Override public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) { return Optional.empty(); }
        @Override public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) { return Optional.empty(); }
        @Override public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) { stepsStore.put(checkpointId, new ArrayList<>(steps)); }
        @Override public List<ReasoningStepDto> loadSteps(Long checkpointId) { return stepsStore.getOrDefault(checkpointId, List.of()); }
        @Override public void deleteSteps(Long checkpointId) { stepsStore.remove(checkpointId); }
        @Override public List<CheckpointDto> findByStatus(String status) { return List.of(); }
        @Override public boolean isCheckpointExpired(String sessionId, long timeoutHours) { return false; }
        @Override public boolean isSessionActive(String sessionId) { return true; }
        @Override public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) { return Optional.empty(); }
        @Override public List<CheckpointDto> findInProgressZombies(Instant cutoff) { return List.of(); }
        @Override public boolean hasPendingApproval(String sessionId) { return false; }
        @Override public void markCheckpointsFailed(String sessionId) {}
        @Override public void deleteCheckpoint(String sessionId, String role) {}
        @Override public CheckpointUpsertResult upsertWithSteps(String sessionId, String agentRole, String stateJson, String autonomy, int iterationsUsed, String originalGoal, String contextDataJson) { return null; }
        @Override public CheckpointUpsertResult upsertAndFinalize(String sessionId, String agentRole, String stateJson, String finalStatus, List<ReasoningStepDto> steps, String originalGoal, String contextDataJson) { return null; }
        @Override public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) { return List.of(); }
        @Override public void purgeOldChatHistory(Instant cutoff) {}
        @Override public void markFailed(Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {}
        @Override public void markCompleted(Long checkpointId) {}
        @Override public void markOrphaned(Long checkpointId, String reason) {}
        @Override public void archive(Long checkpointId) {}
        @Override public boolean claimForRetry(Long id) { return false; }
        @Override public void releaseResumeClaim(String sessionId, String agentRole) {}
        @Override public boolean atomicUpdateBatchToolDecision(String sessionId, String agentRole, String toolName, String decision) { return false; }
        @Override public boolean hasPendingApprovalForChat(String chatId) { return false; }
    }

    static class InMemoryCronStore implements CronStorePort {
        final Map<String, AgentCronDto> store = new ConcurrentHashMap<>();

        @Override
        public List<AgentCronDto> findDueCrons() {
            return store.values().stream()
                .filter(c -> "ACTIVE".equals(c.status()) && c.nextFireTime() != null && c.nextFireTime().isBefore(Instant.now()))
                .collect(Collectors.toList());
        }
        @Override
        public List<AgentCronDto> findByUserId(String userId) {
            return store.values().stream().filter(c -> userId.equals(c.userId())).collect(Collectors.toList());
        }
        @Override
        public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
            return store.values().stream().filter(c -> userId.equals(c.userId()) && "AWAITING_APPROVAL".equals(c.status())).collect(Collectors.toList());
        }
        @Override
        public List<AgentCronDto> findAllAwaitingApproval() {
            return store.values().stream().filter(c -> "AWAITING_APPROVAL".equals(c.status())).collect(Collectors.toList());
        }
        @Override
        public Optional<AgentCronDto> findById(String id) { return Optional.ofNullable(store.get(id)); }
        @Override
        public boolean claimForFire(String id) {
            AgentCronDto c = store.get(id);
            if (c != null && "ACTIVE".equals(c.status())) {
                store.put(id, copyWithStatus(c, "RUNNING"));
                return true;
            }
            return false;
        }
        @Override public void releaseClaimToActive(String id) {
            AgentCronDto c = store.get(id);
            if (c != null && "RUNNING".equals(c.status())) store.put(id, copyWithStatus(c, "ACTIVE"));
        }
        @Override public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "ACTIVE"));
        }
        @Override public void markCompleted(String id, Instant lastFireTime) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "COMPLETED"));
        }
        @Override public void markAwaitingApproval(String id) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "AWAITING_APPROVAL"));
        }
        @Override public void markRunning(String id) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "RUNNING"));
        }
        @Override public void setDeliveryId(String id, String deliveryId) {}
        @Override public void markFailed(String id, String errorReason) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "FAILED"));
        }
        @Override public void markSkipped(String id, Instant nextFireTime, Instant now) {}
        @Override public void reclaimStaleCrons(Instant threshold) {}
        @Override public void save(AgentCronDto dto) { store.put(dto.id(), dto); }
        @Override public int deleteByIdAndUserId(String id, String userId) { return 0; }
        @Override public long deleteAllByUserId(String userId) { return 0; }
        @Override public long deleteAllBySessionId(String sessionId) { return 0; }

        private AgentCronDto copyWithStatus(AgentCronDto c, String status) {
            return new AgentCronDto(c.id(), c.sessionId(), c.userId(), c.chatId(), c.channelType(),
                c.channelPlatformId(), c.cronExpression(), c.timezone(), c.runOnce(), c.goal(),
                c.nextFireTime(), c.lastFireTime(), status, c.executionRole(), c.errorReason(),
                c.lastDeliveryId(), c.createdAt(), Instant.now());
        }
    }

    static class InMemoryAuditEventStore implements AuditEventStorePort {
        final Map<String, List<AuditEventDto>> store = new ConcurrentHashMap<>();

        @Override
        public void save(AuditEventDto dto) {
            store.computeIfAbsent(dto.sessionId(), k -> new ArrayList<>()).add(dto);
        }
        @Override
        public Optional<String> findLatestHash(String sessionId) {
            List<AuditEventDto> events = store.get(sessionId);
            if (events == null || events.isEmpty()) return Optional.empty();
            return Optional.of(events.get(events.size() - 1).hmacHash());
        }
        @Override
        public List<AuditEventDto> findBySessionId(String sessionId) {
            return new ArrayList<>(store.getOrDefault(sessionId, List.of()));
        }
        @Override
        public long countByIdempotencyKey(String key) {
            return store.values().stream().flatMap(List::stream).filter(e -> key.equals(e.idempotencyKey())).count();
        }
        @Override
        public long countEventsByType(String sessionId, String eventType) {
            return store.getOrDefault(sessionId, List.of()).stream().filter(e -> eventType.equals(e.eventType())).count();
        }
        @Override
        public long getMaxSequence(String sessionId) {
            return store.getOrDefault(sessionId, List.of()).stream().mapToLong(AuditEventDto::sequence).max().orElse(0L);
        }
    }

    static class InMemoryAdvisoryLock implements AdvisoryLockPort {
        final Map<String, Boolean> locks = new ConcurrentHashMap<>();

        @Override
        public boolean tryLock(String lockKey, int timeoutSeconds) {
            return locks.putIfAbsent(lockKey, true) == null;
        }
        @Override
        public void unlock(String lockKey) { locks.remove(lockKey); }
        @Override
        public boolean isLocked(String lockKey) { return locks.containsKey(lockKey); }
    }
}
