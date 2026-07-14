package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private InMemoryMcpConnectionStore mcpConnectionStore;

    @BeforeEach
    void setUp() {
        checkpointStore = new InMemoryCheckpointStore();
        cronStore = new InMemoryCronStore();
        auditStore = new InMemoryAuditEventStore();
        advisoryLock = new InMemoryAdvisoryLock();
        mcpConnectionStore = new InMemoryMcpConnectionStore();
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
        List<ReasoningStepDto> steps =
                List.of(new ReasoningStepDto("thought", "action", "{}", "obs", 100, 50, true, null, null, null));
        CheckpointDto result = checkpointStore.saveCheckpointWithSteps(saved, steps);
        List<ReasoningStepDto> loaded = checkpointStore.loadSteps(result.id());
        assertEquals(1, loaded.size());
        assertEquals("thought", loaded.get(0).thoughtJson());
    }

    @Test
    void checkpointFindAllActive() {
        checkpointStore.save(createCheckpoint("sess-active-1", "IN_PROGRESS"));
        checkpointStore.save(createCheckpoint("sess-active-2", "AWAITING_APPROVAL"));
        checkpointStore.save(createCheckpoint("sess-terminal-1", "COMPLETED"));
        checkpointStore.save(createCheckpoint("sess-terminal-2", "FAILED"));
        List<CheckpointDto> active = checkpointStore.findAllActive();
        assertEquals(2, active.size());
        assertTrue(active.stream()
                .allMatch(c -> "IN_PROGRESS".equals(c.status()) || "AWAITING_APPROVAL".equals(c.status())));
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
                "cron-due",
                "sess-1",
                "user-1",
                "chat-1",
                "telegram",
                "plat-1",
                "0 0 * * * ?",
                "UTC",
                false,
                "goal",
                Instant.now().minusSeconds(60),
                Instant.now().minusSeconds(120),
                "ACTIVE",
                "assistant",
                null,
                null,
                Instant.now(),
                Instant.now());
        AgentCronDto notDue = new AgentCronDto(
                "cron-future",
                "sess-2",
                "user-1",
                "chat-1",
                "telegram",
                "plat-1",
                "0 0 * * * ?",
                "UTC",
                false,
                "goal",
                Instant.now().plusSeconds(3600),
                null,
                "ACTIVE",
                "assistant",
                null,
                null,
                Instant.now(),
                Instant.now());
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
                UUID.randomUUID(),
                "sess-10",
                "user-1",
                "assistant",
                "goal-hash",
                "SUCCESS",
                "TOOL_CALL",
                "{}",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0",
                "key-1",
                1L,
                "hash-1",
                null);
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
                UUID.randomUUID(),
                "sess-12",
                "user-1",
                "assistant",
                "gh",
                "SUCCESS",
                "TOOL_CALL",
                "{}",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0",
                "key-1",
                1L,
                "h1",
                null));
        auditStore.save(new AuditEventDto(
                UUID.randomUUID(),
                "sess-12",
                "user-1",
                "assistant",
                "gh",
                "SUCCESS",
                "TOOL_CALL",
                "{}",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0",
                "key-2",
                2L,
                "h2",
                null));
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
                UUID.randomUUID(),
                "sess-14",
                "user-1",
                "assistant",
                "gh",
                "SUCCESS",
                "TOOL_CALL",
                "{}",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0",
                "k1",
                1L,
                "h1",
                null));
        auditStore.save(new AuditEventDto(
                UUID.randomUUID(),
                "sess-14",
                "user-1",
                "assistant",
                "gh",
                "SUCCESS",
                "MESSAGE",
                "{}",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0",
                "k2",
                2L,
                "h2",
                null));
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

    // ── McpConnectionStorePort contract ──

    @Test
    void mcpConnectionSaveFindAndListAreOwnerScoped() {
        McpConnectionDto userOneConnection = createMcpConnection("mcp-1", "user-1", "docs", true);
        McpConnectionDto userTwoConnection = createMcpConnection("mcp-2", "user-2", "docs", true);

        mcpConnectionStore.save(userOneConnection);
        mcpConnectionStore.save(userTwoConnection);

        assertEquals(Optional.of(userOneConnection), mcpConnectionStore.findByIdAndUserId("mcp-1", "user-1"));
        assertTrue(mcpConnectionStore.findByIdAndUserId("mcp-1", "user-2").isEmpty());
        assertEquals(Optional.of(userTwoConnection), mcpConnectionStore.findByNameAndUserId("docs", "user-2"));
        assertEquals(List.of(userOneConnection), mcpConnectionStore.listByUserId("user-1"));
    }

    @Test
    void mcpConnectionListEnabledOnlyReturnsEnabledConnectionsAcrossOwners() {
        McpConnectionDto enabledOne = createMcpConnection("mcp-1", "user-1", "docs", true);
        McpConnectionDto disabled = createMcpConnection("mcp-2", "user-1", "disabled", false);
        McpConnectionDto enabledTwo = createMcpConnection("mcp-3", "user-2", "ops", true);

        mcpConnectionStore.save(enabledOne);
        mcpConnectionStore.save(disabled);
        mcpConnectionStore.save(enabledTwo);

        assertEquals(Set.of(enabledOne, enabledTwo), Set.copyOf(mcpConnectionStore.listEnabled()));
    }

    @Test
    void mcpHeaderSecretRefsAreOwnerScopedAndHeaderNamesAreNormalized() {
        McpConnectionDto connection = createMcpConnection("mcp-1", "user-1", "docs", true);
        mcpConnectionStore.save(connection);

        mcpConnectionStore.replaceHeaderSecretRefs(
                connection.id(),
                "user-1",
                List.of(createMcpHeaderRef("ref-1", connection.id(), "user-1", "Authorization", "vault-auth")));

        List<McpConnectionHeaderSecretRefDto> refs = mcpConnectionStore.listHeaderSecretRefs(connection.id(), "user-1");
        assertEquals(1, refs.size());
        assertEquals("authorization", refs.getFirst().headerName());
        assertTrue(mcpConnectionStore
                .listHeaderSecretRefs(connection.id(), "user-2")
                .isEmpty());
    }

    @Test
    void mcpHeaderSecretRefsRejectCaseInsensitiveDuplicateHeaderNames() {
        McpConnectionDto connection = createMcpConnection("mcp-1", "user-1", "docs", true);
        mcpConnectionStore.save(connection);

        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> mcpConnectionStore.replaceHeaderSecretRefs(
                        connection.id(),
                        "user-1",
                        List.of(
                                createMcpHeaderRef("ref-1", connection.id(), "user-1", "Authorization", "vault-auth-1"),
                                createMcpHeaderRef(
                                        "ref-2", connection.id(), "user-1", "authorization", "vault-auth-2"))));

        assertTrue(thrown.getMessage().contains("Duplicate MCP header name"));
    }

    @Test
    void mcpConnectionDeleteRemovesConnectionAndHeaderRefsForOwnerOnly() {
        McpConnectionDto owned = createMcpConnection("mcp-1", "user-1", "docs", true);
        McpConnectionDto other = createMcpConnection("mcp-2", "user-2", "docs", true);
        mcpConnectionStore.save(owned);
        mcpConnectionStore.save(other);
        mcpConnectionStore.replaceHeaderSecretRefs(
                owned.id(),
                "user-1",
                List.of(createMcpHeaderRef("ref-1", owned.id(), "user-1", "Authorization", "vault-auth")));

        assertEquals(0, mcpConnectionStore.deleteByIdAndUserId(owned.id(), "user-2"));
        assertEquals(1, mcpConnectionStore.deleteByIdAndUserId(owned.id(), "user-1"));

        assertTrue(mcpConnectionStore.findByIdAndUserId(owned.id(), "user-1").isEmpty());
        assertTrue(mcpConnectionStore.listHeaderSecretRefs(owned.id(), "user-1").isEmpty());
        assertEquals(Optional.of(other), mcpConnectionStore.findByIdAndUserId(other.id(), "user-2"));
    }

    @Test
    void mcpCleanupFailuresCanBeSavedListedAndDeleted() {
        McpConnectionCleanupFailureDto failure =
                createMcpCleanupFailure("failure-1", "mcp-1", "user-1", "authorization", "vault-auth", 1);

        mcpConnectionStore.saveCleanupFailure(failure);

        assertEquals(List.of(failure), mcpConnectionStore.listCleanupFailures());
        assertEquals(0, mcpConnectionStore.deleteCleanupFailure(failure.id(), "other-user"));
        assertEquals(List.of(failure), mcpConnectionStore.listCleanupFailures());
        assertEquals(1, mcpConnectionStore.deleteCleanupFailure(failure.id(), failure.userId()));
        assertTrue(mcpConnectionStore.listCleanupFailures().isEmpty());
    }

    // ── Helper factory methods ──

    private CheckpointDto createCheckpoint(String sessionId, String status) {
        return new CheckpointDto(
                null,
                sessionId,
                "role",
                status,
                "FULL",
                "{}",
                "plat-1",
                "telegram",
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
    }

    private CheckpointDto createCheckpointWithId(String sessionId, String status) {
        return new CheckpointDto(
                null,
                sessionId,
                "role",
                status,
                "FULL",
                "{}",
                "plat-1",
                "telegram",
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
    }

    private AgentCronDto createCron(String id, String userId, String status) {
        return new AgentCronDto(
                id,
                "sess-" + id,
                userId,
                "chat-1",
                "telegram",
                "plat-1",
                "0 0 * * * ?",
                "UTC",
                false,
                "goal-" + id,
                Instant.now().plusSeconds(3600),
                null,
                status,
                "assistant",
                null,
                null,
                Instant.now(),
                Instant.now());
    }

    private AuditEventDto createAuditEvent(String sessionId, Long seq, String hash, String key) {
        return new AuditEventDto(
                UUID.randomUUID(),
                sessionId,
                "user-1",
                "assistant",
                "gh",
                "SUCCESS",
                "EVENT",
                "{}",
                100,
                50,
                BigDecimal.ZERO,
                Instant.now(),
                1,
                "1.0",
                key,
                seq,
                hash,
                null);
    }

    private McpConnectionDto createMcpConnection(String id, String userId, String name, boolean enabled) {
        Instant now = Instant.now();
        return new McpConnectionDto(
                id, userId, name, "HTTP_SSE", "https://example.test/" + id, "NETWORK", enabled, now, now);
    }

    private McpConnectionHeaderSecretRefDto createMcpHeaderRef(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef) {
        Instant now = Instant.now();
        return new McpConnectionHeaderSecretRefDto(id, connectionId, userId, headerName, vaultKeyRef, now, now);
    }

    private McpConnectionCleanupFailureDto createMcpCleanupFailure(
            String id, String connectionId, String userId, String headerName, String vaultKeyRef, int attempts) {
        Instant now = Instant.now();
        return new McpConnectionCleanupFailureDto(
                id, connectionId, userId, headerName, vaultKeyRef, "cleanup failed", attempts, now, now);
    }

    // ── In-memory mock implementations ──

    static class InMemoryMcpConnectionStore implements McpConnectionStorePort {
        final Map<String, McpConnectionDto> connections = new ConcurrentHashMap<>();
        final Map<String, List<McpConnectionHeaderSecretRefDto>> headerRefs = new ConcurrentHashMap<>();
        final Map<String, McpConnectionCleanupFailureDto> cleanupFailures = new ConcurrentHashMap<>();

        @Override
        public void save(McpConnectionDto connection) {
            connections.put(connection.id(), connection);
        }

        @Override
        public Optional<McpConnectionDto> findByIdAndUserId(String id, String userId) {
            return Optional.ofNullable(connections.get(id)).filter(connection -> userId.equals(connection.userId()));
        }

        @Override
        public Optional<McpConnectionDto> findByNameAndUserId(String name, String userId) {
            return connections.values().stream()
                    .filter(connection -> userId.equals(connection.userId()))
                    .filter(connection -> name.equals(connection.name()))
                    .findFirst();
        }

        @Override
        public List<McpConnectionDto> listByUserId(String userId) {
            return connections.values().stream()
                    .filter(connection -> userId.equals(connection.userId()))
                    .toList();
        }

        @Override
        public List<McpConnectionDto> listEnabled() {
            return connections.values().stream()
                    .filter(McpConnectionDto::enabled)
                    .toList();
        }

        @Override
        public int deleteByIdAndUserId(String id, String userId) {
            Optional<McpConnectionDto> existing = findByIdAndUserId(id, userId);
            if (existing.isEmpty()) return 0;
            connections.remove(id);
            headerRefs.remove(id);
            return 1;
        }

        @Override
        public void replaceHeaderSecretRefs(
                String connectionId, String userId, List<McpConnectionHeaderSecretRefDto> references) {
            if (findByIdAndUserId(connectionId, userId).isEmpty()) {
                throw new IllegalArgumentException("MCP connection not found: " + connectionId);
            }
            headerRefs.put(connectionId, normalizeAndValidate(connectionId, userId, references));
        }

        @Override
        public List<McpConnectionHeaderSecretRefDto> listHeaderSecretRefs(String connectionId, String userId) {
            if (findByIdAndUserId(connectionId, userId).isEmpty()) return List.of();
            return headerRefs.getOrDefault(connectionId, List.of()).stream()
                    .filter(reference -> userId.equals(reference.userId()))
                    .toList();
        }

        @Override
        public void saveCleanupFailure(McpConnectionCleanupFailureDto failure) {
            cleanupFailures.put(failure.id(), failure);
        }

        @Override
        public List<McpConnectionCleanupFailureDto> listCleanupFailures() {
            return cleanupFailures.values().stream().toList();
        }

        @Override
        public int deleteCleanupFailure(String id, String userId) {
            McpConnectionCleanupFailureDto failure = cleanupFailures.get(id);
            if (failure == null || !failure.userId().equals(userId)) return 0;
            cleanupFailures.remove(id);
            return 1;
        }

        private static List<McpConnectionHeaderSecretRefDto> normalizeAndValidate(
                String connectionId, String userId, List<McpConnectionHeaderSecretRefDto> references) {
            Set<String> normalizedNames = ConcurrentHashMap.newKeySet();
            return references.stream()
                    .map(reference -> normalizeReference(connectionId, userId, reference))
                    .peek(reference -> {
                        if (!normalizedNames.add(reference.headerName())) {
                            throw new IllegalArgumentException("Duplicate MCP header name: " + reference.headerName());
                        }
                    })
                    .toList();
        }

        private static McpConnectionHeaderSecretRefDto normalizeReference(
                String connectionId, String userId, McpConnectionHeaderSecretRefDto reference) {
            if (!connectionId.equals(reference.connectionId()) || !userId.equals(reference.userId())) {
                throw new IllegalArgumentException("MCP header reference owner mismatch");
            }
            return new McpConnectionHeaderSecretRefDto(
                    reference.id(),
                    reference.connectionId(),
                    reference.userId(),
                    reference.headerName().toLowerCase(Locale.ROOT),
                    reference.vaultKeyRef(),
                    reference.createdAt(),
                    reference.updatedAt());
        }
    }

    static class InMemoryCheckpointStore implements CheckpointStorePort {
        private static final Set<String> TERMINAL_STATUSES = Set.of(
                "COMPLETED", "FAILED", "LOOP_EXCEEDED", "DENIED", "COMPROMISED", "ARCHIVED", "ORPHANED", "RESUMING");

        final Map<Long, CheckpointDto> store = new ConcurrentHashMap<>();
        final Map<Long, List<ReasoningStepDto>> stepsStore = new ConcurrentHashMap<>();
        final AtomicLong idSeq = new AtomicLong(1);

        @Override
        public CheckpointDto save(CheckpointDto dto) {
            if (dto.id() == null) {
                dto = new CheckpointDto(
                        idSeq.getAndIncrement(),
                        dto.sessionId(),
                        dto.agentRole(),
                        dto.status(),
                        dto.autonomyLevel(),
                        dto.stateJson(),
                        dto.channelPlatformId(),
                        dto.channelType(),
                        dto.originalGoal(),
                        dto.failedAt(),
                        dto.completedAt(),
                        dto.failReason(),
                        dto.pendingToolName(),
                        dto.batchToolDecisions(),
                        dto.pendingToolParametersJson(),
                        dto.preExecutedResults(),
                        dto.maxIterations(),
                        dto.iterationsUsed(),
                        dto.retryAfterMs(),
                        dto.systemNote(),
                        dto.contextDataJson(),
                        dto.errorCode(),
                        dto.traceId(),
                        dto.rootCauseClass(),
                        dto.parentCheckpointId(),
                        dto.depth(),
                        dto.createdAt(),
                        Instant.now());
            }
            store.put(dto.id(), dto);
            return dto;
        }

        @Override
        public CheckpointDto saveCheckpointWithSteps(CheckpointDto checkpoint, List<ReasoningStepDto> steps) {
            CheckpointDto saved = save(checkpoint);
            saveSteps(saved.id(), steps);
            return saved;
        }

        @Override
        public Optional<CheckpointDto> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<CheckpointDto> findBySessionAndRole(String sessionId, String agentRole) {
            return Optional.empty();
        }

        @Override
        public List<CheckpointDto> findActiveBySession(String sessionId) {
            return store.values().stream()
                    .filter(c -> c.sessionId().equals(sessionId)
                            && !"COMPLETED".equals(c.status())
                            && !"FAILED".equals(c.status()))
                    .collect(Collectors.toList());
        }

        @Override
        public void transitionStatus(Long id, String newStatus) {
            store.computeIfPresent(
                    id,
                    (k, v) -> new CheckpointDto(
                            v.id(),
                            v.sessionId(),
                            v.agentRole(),
                            newStatus,
                            v.autonomyLevel(),
                            v.stateJson(),
                            v.channelPlatformId(),
                            v.channelType(),
                            v.originalGoal(),
                            v.failedAt(),
                            v.completedAt(),
                            v.failReason(),
                            v.pendingToolName(),
                            v.batchToolDecisions(),
                            v.pendingToolParametersJson(),
                            v.preExecutedResults(),
                            v.maxIterations(),
                            v.iterationsUsed(),
                            v.retryAfterMs(),
                            v.systemNote(),
                            v.contextDataJson(),
                            v.errorCode(),
                            v.traceId(),
                            v.rootCauseClass(),
                            v.parentCheckpointId(),
                            v.depth(),
                            v.createdAt(),
                            Instant.now()));
        }

        @Override
        public boolean atomicTransition(Long id, String expectedStatus, String newStatus) {
            CheckpointDto existing = store.get(id);
            if (existing != null && existing.status().equals(expectedStatus)) {
                transitionStatus(id, newStatus);
                return true;
            }
            return false;
        }

        @Override
        public boolean claimForResume(String sessionId, String agentRole) {
            return false;
        }

        @Override
        public List<CheckpointDto> findStaleApprovals(Instant cutoff) {
            return List.of();
        }

        @Override
        public Optional<CheckpointDto> findLatestAwaitingApprovalBySessionContaining(String segment) {
            return Optional.empty();
        }

        @Override
        public Optional<CheckpointDto> findLatestBySession(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<CheckpointDto> findLatestNonArchivedBySession(String sessionId) {
            return Optional.empty();
        }

        @Override
        public Optional<CheckpointDto> findLatestBySessionAndStatus(String sessionId, String status) {
            return Optional.empty();
        }

        @Override
        public void saveSteps(Long checkpointId, List<ReasoningStepDto> steps) {
            stepsStore.put(checkpointId, new ArrayList<>(steps));
        }

        @Override
        public List<ReasoningStepDto> loadSteps(Long checkpointId) {
            return stepsStore.getOrDefault(checkpointId, List.of());
        }

        @Override
        public void deleteSteps(Long checkpointId) {
            stepsStore.remove(checkpointId);
        }

        @Override
        public List<CheckpointDto> findByStatus(String status) {
            return List.of();
        }

        @Override
        public List<CheckpointDto> findAllActive() {
            return store.values().stream()
                    .filter(c -> !TERMINAL_STATUSES.contains(c.status()))
                    .collect(Collectors.toList());
        }

        @Override
        public boolean isCheckpointExpired(String sessionId, long timeoutHours) {
            return false;
        }

        @Override
        public boolean isSessionActive(String sessionId) {
            return true;
        }

        @Override
        public Optional<CheckpointDto> findLatestAwaitingApproval(String sessionId) {
            return Optional.empty();
        }

        @Override
        public List<CheckpointDto> findInProgressZombies(Instant cutoff) {
            return List.of();
        }

        @Override
        public boolean hasPendingApproval(String sessionId) {
            return false;
        }

        @Override
        public void markCheckpointsFailed(String sessionId) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void deleteCheckpoint(String sessionId, String role) {}
        // Stub: not exercised by current contract tests.

        @Override
        public CheckpointUpsertResult upsertWithSteps(
                String sessionId,
                String agentRole,
                String stateJson,
                String autonomy,
                int iterationsUsed,
                String originalGoal,
                String contextDataJson) {
            return null;
        }

        @Override
        public CheckpointUpsertResult upsertAndFinalize(
                String sessionId,
                String agentRole,
                String stateJson,
                String finalStatus,
                List<ReasoningStepDto> steps,
                String originalGoal,
                String contextDataJson) {
            return null;
        }

        @Override
        public List<CheckpointDto> recoverInProgressZombies(Instant inProgressCutoff, String failReason) {
            return List.of();
        }

        @Override
        public void purgeOldChatHistory(Instant cutoff) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void markFailed(
                Long checkpointId, String failReason, String errorCode, String traceId, String rootCauseClass) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void markCompleted(Long checkpointId) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void markOrphaned(Long checkpointId, String reason) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void archive(Long checkpointId) {}
        // Stub: not exercised by current contract tests.

        @Override
        public boolean claimForRetry(Long id) {
            return false;
        }

        @Override
        public void releaseResumeClaim(String sessionId, String agentRole) {}
        // Stub: not exercised by current contract tests.

        @Override
        public boolean atomicUpdateBatchToolDecision(
                String sessionId, String agentRole, String toolName, String decision) {
            return false;
        }

        @Override
        public boolean hasPendingApprovalForChat(String chatId) {
            return false;
        }

        @Override
        public void stampChannelInfo(Long checkpointId, String channelType, String channelPlatformId) {}
        // Stub: not exercised by current contract tests.
    }

    static class InMemoryCronStore implements CronStorePort {
        final Map<String, AgentCronDto> store = new ConcurrentHashMap<>();

        @Override
        public List<AgentCronDto> findDueCrons() {
            return store.values().stream()
                    .filter(c -> "ACTIVE".equals(c.status())
                            && c.nextFireTime() != null
                            && c.nextFireTime().isBefore(Instant.now()))
                    .collect(Collectors.toList());
        }

        @Override
        public List<AgentCronDto> findByUserId(String userId) {
            return store.values().stream()
                    .filter(c -> userId.equals(c.userId()))
                    .collect(Collectors.toList());
        }

        @Override
        public List<AgentCronDto> findAwaitingApprovalByUserId(String userId) {
            return store.values().stream()
                    .filter(c -> userId.equals(c.userId()) && "AWAITING_APPROVAL".equals(c.status()))
                    .collect(Collectors.toList());
        }

        @Override
        public List<AgentCronDto> findAllAwaitingApproval() {
            return store.values().stream()
                    .filter(c -> "AWAITING_APPROVAL".equals(c.status()))
                    .collect(Collectors.toList());
        }

        @Override
        public Optional<AgentCronDto> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public boolean claimForFire(String id) {
            AgentCronDto c = store.get(id);
            if (c != null && "ACTIVE".equals(c.status())) {
                store.put(id, copyWithStatus(c, "RUNNING"));
                return true;
            }
            return false;
        }

        @Override
        public void releaseClaimToActive(String id) {
            AgentCronDto c = store.get(id);
            if (c != null && "RUNNING".equals(c.status())) store.put(id, copyWithStatus(c, "ACTIVE"));
        }

        @Override
        public void markFired(String id, Instant lastFireTime, Instant nextFireTime) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "ACTIVE"));
        }

        @Override
        public void markCompleted(String id, Instant lastFireTime) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "COMPLETED"));
        }

        @Override
        public void markAwaitingApproval(String id) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "AWAITING_APPROVAL"));
        }

        @Override
        public void markRunning(String id) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "RUNNING"));
        }

        @Override
        public void setDeliveryId(String id, String deliveryId) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void markFailed(String id, String errorReason) {
            AgentCronDto c = store.get(id);
            if (c != null) store.put(id, copyWithStatus(c, "FAILED"));
        }

        @Override
        public void markSkipped(String id, Instant nextFireTime, Instant now) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void reclaimStaleCrons(Instant threshold) {}
        // Stub: not exercised by current contract tests.

        @Override
        public void save(AgentCronDto dto) {
            store.put(dto.id(), dto);
        }

        @Override
        public int deleteByIdAndUserId(String id, String userId) {
            return 0;
        }

        @Override
        public long deleteAllByUserId(String userId) {
            return 0;
        }

        @Override
        public long deleteAllBySessionId(String sessionId) {
            return 0;
        }

        @Override
        public List<AgentCronDto> findAll(String status, int offset, int limit) {
            List<AgentCronDto> all = store.values().stream()
                    .filter(c -> status == null || status.isBlank() || status.equals(c.status()))
                    .sorted((a, b) -> {
                        if (a.nextFireTime() == null && b.nextFireTime() == null) return 0;
                        if (a.nextFireTime() == null) return 1;
                        if (b.nextFireTime() == null) return -1;
                        return a.nextFireTime().compareTo(b.nextFireTime());
                    })
                    .collect(Collectors.toList());
            int end = Math.min(offset + limit, all.size());
            if (offset >= all.size()) return List.of();
            return all.subList(offset, end);
        }

        @Override
        public long countAll(String status) {
            if (status != null && !status.isBlank()) {
                return store.values().stream()
                        .filter(c -> status.equals(c.status()))
                        .count();
            }
            return store.size();
        }

        @Override
        public List<AgentCronDto> findByUserId(String userId, String status, int offset, int limit) {
            List<AgentCronDto> all = store.values().stream()
                    .filter(c -> userId.equals(c.userId()))
                    .filter(c -> status == null || status.isBlank() || status.equals(c.status()))
                    .sorted((a, b) -> {
                        if (a.nextFireTime() == null && b.nextFireTime() == null) return 0;
                        if (a.nextFireTime() == null) return 1;
                        if (b.nextFireTime() == null) return -1;
                        return a.nextFireTime().compareTo(b.nextFireTime());
                    })
                    .collect(Collectors.toList());
            int end = Math.min(offset + limit, all.size());
            if (offset >= all.size()) return List.of();
            return all.subList(offset, end);
        }

        @Override
        public long countByUserId(String userId, String status) {
            if (status != null && !status.isBlank()) {
                return store.values().stream()
                        .filter(c -> userId.equals(c.userId()) && status.equals(c.status()))
                        .count();
            }
            return store.values().stream()
                    .filter(c -> userId.equals(c.userId()))
                    .count();
        }

        private AgentCronDto copyWithStatus(AgentCronDto c, String status) {
            return new AgentCronDto(
                    c.id(),
                    c.sessionId(),
                    c.userId(),
                    c.chatId(),
                    c.channelType(),
                    c.channelPlatformId(),
                    c.cronExpression(),
                    c.timezone(),
                    c.runOnce(),
                    c.goal(),
                    c.nextFireTime(),
                    c.lastFireTime(),
                    status,
                    c.executionRole(),
                    c.errorReason(),
                    c.lastDeliveryId(),
                    c.createdAt(),
                    Instant.now());
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
            return store.values().stream()
                    .flatMap(List::stream)
                    .filter(e -> key.equals(e.idempotencyKey()))
                    .count();
        }

        @Override
        public long countEventsByType(String sessionId, String eventType) {
            return store.getOrDefault(sessionId, List.of()).stream()
                    .filter(e -> eventType.equals(e.eventType()))
                    .count();
        }

        @Override
        public long getMaxSequence(String sessionId) {
            return store.getOrDefault(sessionId, List.of()).stream()
                    .mapToLong(AuditEventDto::sequence)
                    .max()
                    .orElse(0L);
        }

        @Override
        public Optional<AuditEventDto> findById(UUID id) {
            if (id == null) return Optional.empty();
            return store.values().stream()
                    .flatMap(List::stream)
                    .filter(e -> id.equals(e.id()))
                    .findFirst();
        }

        @Override
        public List<AuditEventDto> findAll(
                String sessionId, String eventType, Instant from, Instant to, int offset, int limit) {
            List<AuditEventDto> results = new ArrayList<>();
            if (sessionId != null && !sessionId.isBlank()) {
                results.addAll(store.getOrDefault(sessionId, List.of()));
            } else {
                store.values().forEach(results::addAll);
            }
            if (eventType != null && !eventType.isBlank()) {
                results = results.stream()
                        .filter(e -> eventType.equals(e.eventType()))
                        .toList();
            }
            if (from != null) {
                results = results.stream()
                        .filter(e -> e.timestamp() != null && !e.timestamp().isBefore(from))
                        .toList();
            }
            if (to != null) {
                results = results.stream()
                        .filter(e -> e.timestamp() != null && !e.timestamp().isAfter(to))
                        .toList();
            }
            results = results.stream()
                    .sorted((a, b) -> {
                        if (a.timestamp() == null && b.timestamp() == null) return 0;
                        if (a.timestamp() == null) return 1;
                        if (b.timestamp() == null) return -1;
                        return b.timestamp().compareTo(a.timestamp());
                    })
                    .toList();
            int end = Math.min(offset + limit, results.size());
            if (offset >= results.size()) return List.of();
            return results.subList(offset, end);
        }

        @Override
        public long countAll(String sessionId, String eventType, Instant from, Instant to) {
            return findAll(sessionId, eventType, from, to, 0, Integer.MAX_VALUE).size();
        }
    }

    static class InMemoryCronRunStore implements CronRunStorePort {
        final Map<String, CronRunDto> store = new ConcurrentHashMap<>();

        @Override
        public void save(CronRunDto dto) {
            store.put(dto.id(), dto);
        }

        @Override
        public Optional<CronRunDto> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<CronRunDto> findByCronId(String cronId, int offset, int limit) {
            List<CronRunDto> all = store.values().stream()
                    .filter(r -> cronId.equals(r.cronId()))
                    .sorted((a, b) -> {
                        if (a.startedAt() == null && b.startedAt() == null) return 0;
                        if (a.startedAt() == null) return 1;
                        if (b.startedAt() == null) return -1;
                        return b.startedAt().compareTo(a.startedAt());
                    })
                    .collect(Collectors.toList());
            int end = Math.min(offset + limit, all.size());
            if (offset >= all.size()) return List.of();
            return all.subList(offset, end);
        }

        @Override
        public long countByCronId(String cronId) {
            return store.values().stream()
                    .filter(r -> cronId.equals(r.cronId()))
                    .count();
        }

        @Override
        public List<CronRunDto> findAll(String status, int offset, int limit) {
            List<CronRunDto> all = store.values().stream()
                    .filter(r -> status == null || status.isBlank() || status.equals(r.status()))
                    .sorted((a, b) -> {
                        if (a.startedAt() == null && b.startedAt() == null) return 0;
                        if (a.startedAt() == null) return 1;
                        if (b.startedAt() == null) return -1;
                        return b.startedAt().compareTo(a.startedAt());
                    })
                    .collect(Collectors.toList());
            int end = Math.min(offset + limit, all.size());
            if (offset >= all.size()) return List.of();
            return all.subList(offset, end);
        }

        @Override
        public long countAll(String status) {
            if (status != null && !status.isBlank()) {
                return store.values().stream()
                        .filter(r -> status.equals(r.status()))
                        .count();
            }
            return store.size();
        }
    }

    static class InMemoryAdvisoryLock implements AdvisoryLockPort {
        final Map<String, Boolean> locks = new ConcurrentHashMap<>();

        @Override
        public boolean tryLock(String lockKey, int timeoutSeconds) {
            return locks.putIfAbsent(lockKey, true) == null;
        }

        @Override
        public void unlock(String lockKey) {
            locks.remove(lockKey);
        }

        @Override
        public boolean isLocked(String lockKey) {
            return locks.containsKey(lockKey);
        }
    }
}
