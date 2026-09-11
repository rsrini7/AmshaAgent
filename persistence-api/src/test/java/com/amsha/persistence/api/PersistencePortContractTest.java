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
    private InMemoryIdentityStore identityStore;
    private InMemoryApiCredentialStore apiCredentialStore;
    private InMemoryWebSessionStore webSessionStore;
    private InMemoryIdentityInvitationStore identityInvitationStore;
    private InMemoryBootstrapStateStore bootstrapStateStore;

    @BeforeEach
    void setUp() {
        checkpointStore = new InMemoryCheckpointStore();
        cronStore = new InMemoryCronStore();
        auditStore = new InMemoryAuditEventStore();
        advisoryLock = new InMemoryAdvisoryLock();
        mcpConnectionStore = new InMemoryMcpConnectionStore();
        identityStore = new InMemoryIdentityStore();
        apiCredentialStore = new InMemoryApiCredentialStore();
        webSessionStore = new InMemoryWebSessionStore();
        identityInvitationStore = new InMemoryIdentityInvitationStore();
        bootstrapStateStore = new InMemoryBootstrapStateStore();
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

    @Test
    void checkpointFindActiveBySessionTreeIncludesRootAndDelegates() {
        checkpointStore.save(createCheckpointWithId("tree-root", "IN_PROGRESS"));
        checkpointStore.save(createCheckpointWithId("tree-root:delegate:sub:1", "RESUMING"));
        checkpointStore.save(createCheckpointWithId("tree-root:delegate:sub:2", "COMPLETED"));
        checkpointStore.save(createCheckpointWithId("tree-root:delegate:sub:3", "COMPROMISED"));
        checkpointStore.save(createCheckpointWithId("tree-root:delegate:sub:4", "LOOP_EXCEEDED"));
        checkpointStore.save(createCheckpointWithId("tree-root:delegate:sub:5", "ORPHANED"));
        checkpointStore.save(createCheckpointWithId("tree-root:delegate:sub:6", "APPROVED"));
        checkpointStore.save(createCheckpointWithId("other-session", "IN_PROGRESS"));

        List<CheckpointDto> active = checkpointStore.findActiveBySessionTree("tree-root");
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(c -> "tree-root".equals(c.sessionId())));
        assertTrue(active.stream().anyMatch(c -> "tree-root:delegate:sub:1".equals(c.sessionId())));
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

    @Test
    void advisoryLockRenewHeldLockSuccess() {
        assertTrue(advisoryLock.tryLock("lock-renew", 30));
        assertTrue(advisoryLock.renew("lock-renew", 60));
    }

    @Test
    void advisoryLockRenewUnheldLockFails() {
        assertFalse(advisoryLock.renew("unheld-lock", 60));
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

    // ── IdentityStorePort contract ──

    @Test
    void identityStoreUserContract() {
        IdentityUserDto user = new IdentityUserDto("user-100", "Alice", "ACTIVE", Instant.now(), Instant.now());
        identityStore.saveUser(user);
        Optional<IdentityUserDto> found = identityStore.findUserById("user-100");
        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().displayName());
        assertEquals(1, identityStore.listUsers().size());
    }

    @Test
    void identityStoreExternalIdentityContract() {
        ExternalIdentityDto ext = new ExternalIdentityDto(
                "provider-1", "issuer-1", "sub-1", "user-100", "{}", Instant.now(), Instant.now());
        identityStore.saveExternalIdentity(ext);
        Optional<ExternalIdentityDto> found = identityStore.findExternalIdentity("provider-1", "issuer-1", "sub-1");
        assertTrue(found.isPresent());
        assertEquals("user-100", found.get().userId());

        Optional<ExternalIdentityDto> foundByUser =
                identityStore.findExternalIdentityByUserId("provider-1", "user-100");
        assertTrue(foundByUser.isPresent());

        assertEquals(1, identityStore.listExternalIdentitiesByUserId("user-100").size());
        identityStore.deleteExternalIdentity("provider-1", "issuer-1", "sub-1");
        assertTrue(identityStore
                .findExternalIdentity("provider-1", "issuer-1", "sub-1")
                .isEmpty());
    }

    @Test
    void identityStoreWorkspaceContract() {
        IdentityWorkspaceDto ws =
                new IdentityWorkspaceDto("ws-1", "My Workspace", "user-100", Instant.now(), Instant.now());
        identityStore.saveWorkspace(ws);
        Optional<IdentityWorkspaceDto> found = identityStore.findWorkspaceById("ws-1");
        assertTrue(found.isPresent());
        assertEquals("My Workspace", found.get().displayName());
        assertEquals(1, identityStore.listWorkspaces().size());
    }

    @Test
    void identityStoreMembershipContract() {
        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-1", "user-100", "OWNER", "ACTIVE", 0, Instant.now(), Instant.now());
        identityStore.saveMembership(mem);
        Optional<WorkspaceMembershipDto> found = identityStore.findMembership("ws-1", "user-100");
        assertTrue(found.isPresent());
        assertEquals("OWNER", found.get().role());
        assertEquals(1, identityStore.listMembershipsByWorkspaceId("ws-1").size());
        assertEquals(1, identityStore.listMembershipsByUserId("user-100").size());

        identityStore.deleteMembership("ws-1", "user-100");
        assertTrue(identityStore.findMembership("ws-1", "user-100").isEmpty());
    }

    // ── ApiCredentialStorePort contract ──

    @Test
    void apiCredentialContract() {
        ApiCredentialDto cred = new ApiCredentialDto(
                "cred-1", "user-100", "digest-1", "OWNER", 0, null, null, Instant.now(), Instant.now());
        apiCredentialStore.save(cred);
        Optional<ApiCredentialDto> found = apiCredentialStore.findById("cred-1");
        assertTrue(found.isPresent());
        assertEquals("user-100", found.get().userId());

        Optional<ApiCredentialDto> foundByDigest = apiCredentialStore.findByKeyDigest("digest-1");
        assertTrue(foundByDigest.isPresent());

        assertEquals(1, apiCredentialStore.listByUserId("user-100").size());
        apiCredentialStore.deleteById("cred-1");
        assertTrue(apiCredentialStore.findById("cred-1").isEmpty());
    }

    // ── WebSessionStorePort contract ──

    @Test
    void webSessionContract() {
        Instant now = Instant.now();
        WebSessionDto session = new WebSessionDto(
                "sess-100",
                "user-100",
                "token-digest-1",
                "csrf-1",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                0,
                now,
                now);
        webSessionStore.save(session);
        Optional<WebSessionDto> found = webSessionStore.findById("sess-100");
        assertTrue(found.isPresent());
        assertEquals("user-100", found.get().userId());

        Optional<WebSessionDto> foundByToken = webSessionStore.findByTokenDigest("token-digest-1");
        assertTrue(foundByToken.isPresent());

        assertEquals(1, webSessionStore.listByUserId("user-100").size());

        webSessionStore.deleteExpired(now.plusSeconds(10000));
        assertTrue(webSessionStore.findById("sess-100").isEmpty());
    }

    // ── IdentityInvitationStorePort contract ──

    @Test
    void identityInvitationContract() {
        Instant now = Instant.now();
        IdentityInvitationDto invitation = new IdentityInvitationDto(
                "inv-1", "ws-1", "ADMIN", "token-digest-1", null, now.plusSeconds(3600), null, null, 0, now, now);
        identityInvitationStore.save(invitation);
        Optional<IdentityInvitationDto> found = identityInvitationStore.findById("inv-1");
        assertTrue(found.isPresent());
        assertEquals("ws-1", found.get().workspaceId());

        Optional<IdentityInvitationDto> foundByToken = identityInvitationStore.findByTokenDigest("token-digest-1");
        assertTrue(foundByToken.isPresent());

        assertEquals(1, identityInvitationStore.listByWorkspaceId("ws-1").size());

        identityInvitationStore.deleteExpired(now.plusSeconds(10000));
        assertTrue(identityInvitationStore.findById("inv-1").isEmpty());
    }

    // ── BootstrapStateStorePort contract ──

    @Test
    void bootstrapStateContract() {
        BootstrapStateDto state = new BootstrapStateDto(
                "bootstrap", "UNCLAIMED", "user-100", null, "digest-1", null, null, 0, Instant.now());
        bootstrapStateStore.save(state);
        Optional<BootstrapStateDto> found = bootstrapStateStore.getState();
        assertTrue(found.isPresent());
        assertEquals("UNCLAIMED", found.get().status());
    }

    @Test
    void bootstrapReservationIsAtomic() {
        Instant now = Instant.now();
        // Reserve: only one winner reaches CLAIMING
        Optional<Integer> revA = bootstrapStateStore.reserveFirstOwner("owner-a", "ws-a", now);
        assertTrue(revA.isPresent());
        Optional<Integer> revB = bootstrapStateStore.reserveFirstOwner("owner-b", "ws-b", now);
        assertFalse(revB.isPresent());

        Optional<BootstrapStateDto> reserved = bootstrapStateStore.getState();
        assertTrue(reserved.isPresent());
        assertEquals("CLAIMING", reserved.get().status());
        assertEquals("owner-a", reserved.get().ownerId());

        // Issue credential for the reserved owner
        assertTrue(bootstrapStateStore.recordCredentialIssued(revA.get(), "digest-a", now));
        Optional<BootstrapStateDto> issued = bootstrapStateStore.getState();
        assertEquals("CREDENTIAL_ISSUED", issued.get().status());
        assertEquals(2, issued.get().revision());

        // Confirm bound to the reserved owner
        assertTrue(bootstrapStateStore.confirmClaim(issued.get().revision(), "owner-a", now));
        Optional<BootstrapStateDto> confirmed = bootstrapStateStore.getState();
        assertEquals("CLAIMED", confirmed.get().status());

        // Stale/foreign confirm rejected
        assertFalse(bootstrapStateStore.confirmClaim(1, "owner-a", now));
        assertFalse(bootstrapStateStore.confirmClaim(confirmed.get().revision(), "owner-b", now));
    }

    @Test
    void identityMembershipRevisionBumpsOnSaveAndRejectsStale() {
        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-cas", "user-cas", "OWNER", "ACTIVE", 0, Instant.now(), Instant.now());
        assertTrue(identityStore.saveMembership(mem));
        WorkspaceMembershipDto saved =
                identityStore.findMembership("ws-cas", "user-cas").get();
        assertEquals(0, saved.revision());

        WorkspaceMembershipDto updated =
                new WorkspaceMembershipDto("ws-cas", "user-cas", "MEMBER", "ACTIVE", 0, Instant.now(), Instant.now());
        assertTrue(identityStore.saveMembership(updated));
        WorkspaceMembershipDto after =
                identityStore.findMembership("ws-cas", "user-cas").get();
        assertEquals(1, after.revision());
        assertEquals("MEMBER", after.role());

        WorkspaceMembershipDto stale =
                new WorkspaceMembershipDto("ws-cas", "user-cas", "OWNER", "ACTIVE", 0, Instant.now(), Instant.now());
        assertFalse(identityStore.saveMembership(stale));
    }

    @Test
    void apiCredentialRevisionBumpsOnSaveAndRejectsStale() {
        String credId = "cred-cas";
        ApiCredentialDto cred = new ApiCredentialDto(
                credId, "user-cas", "digest-cas", "OWNER", 0, null, null, Instant.now(), Instant.now());
        assertTrue(apiCredentialStore.save(cred));
        assertEquals(0, apiCredentialStore.findById(credId).get().revision());

        ApiCredentialDto rotated = new ApiCredentialDto(
                credId, "user-cas", "digest-cas2", "OWNER", 0, null, null, Instant.now(), Instant.now());
        assertTrue(apiCredentialStore.save(rotated));
        ApiCredentialDto after = apiCredentialStore.findById(credId).get();
        assertEquals(1, after.revision());
        assertEquals("digest-cas2", after.keyDigest());

        ApiCredentialDto stale = new ApiCredentialDto(
                credId, "user-cas", "digest-cas3", "OWNER", 0, null, null, Instant.now(), Instant.now());
        assertFalse(apiCredentialStore.save(stale));
    }

    @Test
    void webSessionRevisionBumpsOnSaveAndRejectsStale() {
        String sessionId = "sess-cas";
        Instant now = Instant.now();
        WebSessionDto session = new WebSessionDto(
                sessionId,
                "user-cas",
                "token-cas",
                "csrf-cas",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                0,
                now,
                now);
        assertTrue(webSessionStore.save(session));
        assertEquals(0, webSessionStore.findById(sessionId).get().revision());

        WebSessionDto rotated = new WebSessionDto(
                sessionId,
                "user-cas",
                "token-cas2",
                "csrf-cas",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                0,
                now,
                now);
        assertTrue(webSessionStore.save(rotated));
        WebSessionDto after = webSessionStore.findById(sessionId).get();
        assertEquals(1, after.revision());
        assertEquals("token-cas2", after.tokenDigest());

        WebSessionDto stale = new WebSessionDto(
                sessionId,
                "user-cas",
                "token-cas3",
                "csrf-cas",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                0,
                now,
                now);
        assertFalse(webSessionStore.save(stale));
    }

    @Test
    void identityInvitationRevisionBumpsOnSaveAndRejectsStale() {
        String invitationId = "inv-cas";
        Instant now = Instant.now();
        IdentityInvitationDto invitation = new IdentityInvitationDto(
                invitationId, "ws-cas", "MEMBER", "token-cas", null, now.plusSeconds(3600), null, null, 0, now, now);
        assertTrue(identityInvitationStore.save(invitation));
        assertEquals(0, identityInvitationStore.findById(invitationId).get().revision());

        IdentityInvitationDto rotated = new IdentityInvitationDto(
                invitationId, "ws-cas", "ADMIN", "token-cas2", null, now.plusSeconds(3600), null, null, 0, now, now);
        assertTrue(identityInvitationStore.save(rotated));
        IdentityInvitationDto after =
                identityInvitationStore.findById(invitationId).get();
        assertEquals(1, after.revision());
        assertEquals("ADMIN", after.role());

        IdentityInvitationDto stale = new IdentityInvitationDto(
                invitationId, "ws-cas", "MEMBER", "token-cas3", null, now.plusSeconds(3600), null, null, 0, now, now);
        assertFalse(identityInvitationStore.save(stale));
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
        final Map<String, List<McpConnectionToolPolicyDto>> toolPolicies = new ConcurrentHashMap<>();
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
        public List<McpConnectionDto> listPendingApprovals() {
            return connections.values().stream()
                    .filter(connection -> "PENDING_APPROVAL".equals(connection.state()))
                    .toList();
        }

        @Override
        public boolean updateStateIfRevision(McpConnectionDto connection, long expectedRevision) {
            return connections.computeIfPresent(
                            connection.id(),
                            (id, current) -> current.revision() == expectedRevision ? connection : current)
                    == connection;
        }

        @Override
        public List<McpConnectionToolPolicyDto> listToolPolicies(String connectionId, String userId) {
            return toolPolicies.getOrDefault(connectionId, List.of()).stream()
                    .filter(policy -> userId.equals(policy.userId()))
                    .toList();
        }

        @Override
        public void replaceToolPolicies(String connectionId, String userId, List<McpConnectionToolPolicyDto> policies) {
            if (findByIdAndUserId(connectionId, userId).isEmpty()) {
                throw new IllegalArgumentException("MCP connection not found: " + connectionId);
            }
            toolPolicies.put(connectionId, List.copyOf(policies == null ? List.of() : policies));
        }

        @Override
        public int setToolEnabled(String connectionId, String userId, String toolName, boolean enabled) {
            List<McpConnectionToolPolicyDto> existing = listToolPolicies(connectionId, userId);
            if (existing.stream().noneMatch(policy -> toolName.equals(policy.toolName()))) return 0;
            toolPolicies.put(
                    connectionId,
                    existing.stream()
                            .map(policy -> toolName.equals(policy.toolName())
                                    ? new McpConnectionToolPolicyDto(
                                            policy.id(),
                                            policy.connectionId(),
                                            policy.userId(),
                                            policy.toolName(),
                                            policy.available(),
                                            enabled,
                                            policy.description(),
                                            policy.inputSchema(),
                                            policy.schemaHash(),
                                            policy.discoveredAt(),
                                            policy.revision() + 1,
                                            policy.updatedAt())
                                    : policy)
                            .toList());
            return 1;
        }

        @Override
        public int deleteByIdAndUserId(String id, String userId) {
            Optional<McpConnectionDto> existing = findByIdAndUserId(id, userId);
            if (existing.isEmpty()) return 0;
            connections.remove(id);
            headerRefs.remove(id);
            toolPolicies.remove(id);
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
        public List<CheckpointDto> findActiveBySessionTree(String rootSessionId) {
            if (rootSessionId == null) return List.of();
            String delegatePrefix = rootSessionId + ":delegate:";
            return store.values().stream()
                    .filter(c -> c.sessionId() != null
                            && (c.sessionId().equals(rootSessionId)
                                    || c.sessionId().startsWith(delegatePrefix)))
                    .filter(c -> c.status() != null
                            && ("IN_PROGRESS".equals(c.status())
                                    || "RESUMING".equals(c.status())
                                    || "AWAITING_APPROVAL".equals(c.status())
                                    || "AWAITING_RETRY".equals(c.status())))
                    .collect(Collectors.toList());
        }

        @Override
        public List<CheckpointDto> findByStatusAndSessionIds(
                String status, List<String> sessionIds, int limit, int offset) {
            return store.values().stream()
                    .filter(c -> status.equals(c.status()) && sessionIds.contains(c.sessionId()))
                    .sorted((c1, c2) -> Long.compare(c2.id(), c1.id()))
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        @Override
        public long countByStatusAndSessionIds(String status, List<String> sessionIds) {
            return store.values().stream()
                    .filter(c -> status.equals(c.status()) && sessionIds.contains(c.sessionId()))
                    .count();
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
        public boolean claimForFire(String id, String deliveryId) {
            AgentCronDto c = store.get(id);
            if (c != null && "ACTIVE".equals(c.status())) {
                store.put(id, copyWithStatusAndDelivery(c, "RUNNING", deliveryId));
                return true;
            }
            return false;
        }

        @Override
        public boolean claimForResume(String id, String deliveryId) {
            AgentCronDto c = store.get(id);
            if (c != null && ("AWAITING_APPROVAL".equals(c.status()) || "AWAITING_RETRY".equals(c.status()))) {
                store.put(id, copyWithStatusAndDelivery(c, "RUNNING", deliveryId));
                return true;
            }
            return false;
        }

        @Override
        public boolean renewLease(String id, String deliveryId) {
            AgentCronDto c = store.get(id);
            if (c != null && "RUNNING".equals(c.status()) && deliveryId.equals(c.lastDeliveryId())) {
                store.put(id, copyWithStatus(c, "RUNNING"));
                return true;
            }
            return false;
        }

        @Override
        public boolean markCompletedIfOwner(String id, String deliveryId, Instant lastFireTime) {
            AgentCronDto c = store.get(id);
            if (c != null
                    && deliveryId != null
                    && deliveryId.equals(c.lastDeliveryId())
                    && "RUNNING".equals(c.status())) {
                markCompleted(id, lastFireTime);
                return true;
            }
            return false;
        }

        @Override
        public boolean markFiredIfOwner(String id, String deliveryId, Instant lastFireTime, Instant nextFireTime) {
            AgentCronDto c = store.get(id);
            if (c != null
                    && deliveryId != null
                    && deliveryId.equals(c.lastDeliveryId())
                    && "RUNNING".equals(c.status())) {
                markFired(id, lastFireTime, nextFireTime);
                return true;
            }
            return false;
        }

        @Override
        public boolean markAwaitingApprovalIfOwner(String id, String deliveryId) {
            AgentCronDto c = store.get(id);
            if (c != null
                    && deliveryId != null
                    && deliveryId.equals(c.lastDeliveryId())
                    && "RUNNING".equals(c.status())) {
                markAwaitingApproval(id);
                return true;
            }
            return false;
        }

        @Override
        public boolean markAwaitingRetryIfOwner(String id, String deliveryId) {
            AgentCronDto c = store.get(id);
            if (c != null
                    && deliveryId != null
                    && deliveryId.equals(c.lastDeliveryId())
                    && "RUNNING".equals(c.status())) {
                store.put(id, copyWithStatus(c, "AWAITING_RETRY"));
                return true;
            }
            return false;
        }

        @Override
        public boolean markFailedIfOwner(String id, String deliveryId, String errorReason) {
            AgentCronDto c = store.get(id);
            boolean validStatus = c != null
                    && ("RUNNING".equals(c.status())
                            || "AWAITING_APPROVAL".equals(c.status())
                            || "AWAITING_RETRY".equals(c.status()));
            if (c != null && deliveryId != null && deliveryId.equals(c.lastDeliveryId()) && validStatus) {
                markFailed(id, errorReason);
                return true;
            }
            return false;
        }

        @Override
        public boolean markFailedIfStatus(String id, String expectedStatus, String errorReason) {
            AgentCronDto c = store.get(id);
            if (c != null && (expectedStatus == null || expectedStatus.equals(c.status()))) {
                markFailed(id, errorReason);
                return true;
            }
            return false;
        }

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

        private AgentCronDto copyWithStatusAndDelivery(AgentCronDto c, String status, String deliveryId) {
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
                    deliveryId,
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

        @Override
        public boolean renew(String lockKey, int timeoutSeconds) {
            return locks.containsKey(lockKey);
        }
    }

    static class InMemoryIdentityStore implements IdentityStorePort {
        final Map<String, IdentityUserDto> users = new ConcurrentHashMap<>();
        final Map<String, ExternalIdentityDto> externalIdentities = new ConcurrentHashMap<>();
        final Map<String, IdentityWorkspaceDto> workspaces = new ConcurrentHashMap<>();
        final Map<String, WorkspaceMembershipDto> memberships = new ConcurrentHashMap<>();

        @Override
        public void saveUser(IdentityUserDto user) {
            users.put(user.userId(), user);
        }

        @Override
        public Optional<IdentityUserDto> findUserById(String userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public List<IdentityUserDto> listUsers() {
            return new ArrayList<>(users.values());
        }

        @Override
        public void saveExternalIdentity(ExternalIdentityDto identity) {
            String key = identity.providerId() + "_" + identity.issuer() + "_" + identity.subject();
            externalIdentities.put(key, identity);
        }

        @Override
        public Optional<ExternalIdentityDto> findExternalIdentity(String providerId, String issuer, String subject) {
            String key = providerId + "_" + issuer + "_" + subject;
            return Optional.ofNullable(externalIdentities.get(key));
        }

        @Override
        public Optional<ExternalIdentityDto> findExternalIdentityByUserId(String providerId, String userId) {
            return externalIdentities.values().stream()
                    .filter(identity -> providerId.equals(identity.providerId()) && userId.equals(identity.userId()))
                    .findFirst();
        }

        @Override
        public List<ExternalIdentityDto> listExternalIdentitiesByUserId(String userId) {
            return externalIdentities.values().stream()
                    .filter(identity -> userId.equals(identity.userId()))
                    .toList();
        }

        @Override
        public void deleteExternalIdentity(String providerId, String issuer, String subject) {
            String key = providerId + "_" + issuer + "_" + subject;
            externalIdentities.remove(key);
        }

        @Override
        public void saveWorkspace(IdentityWorkspaceDto workspace) {
            workspaces.put(workspace.workspaceId(), workspace);
        }

        @Override
        public Optional<IdentityWorkspaceDto> findWorkspaceById(String workspaceId) {
            return Optional.ofNullable(workspaces.get(workspaceId));
        }

        @Override
        public List<IdentityWorkspaceDto> listWorkspaces() {
            return new ArrayList<>(workspaces.values());
        }

        @Override
        public boolean saveMembership(WorkspaceMembershipDto membership) {
            String key = membership.workspaceId() + "_" + membership.userId();
            WorkspaceMembershipDto existing = memberships.get(key);
            if (existing != null && existing.revision() != membership.revision()) {
                return false;
            }
            WorkspaceMembershipDto toStore = existing == null
                    ? membership
                    : new WorkspaceMembershipDto(
                            membership.workspaceId(),
                            membership.userId(),
                            membership.role(),
                            membership.status(),
                            membership.revision() + 1,
                            membership.createdAt(),
                            membership.updatedAt());
            memberships.put(key, toStore);
            return true;
        }

        @Override
        public Optional<WorkspaceMembershipDto> findMembership(String workspaceId, String userId) {
            String key = workspaceId + "_" + userId;
            return Optional.ofNullable(memberships.get(key));
        }

        @Override
        public List<WorkspaceMembershipDto> listMembershipsByWorkspaceId(String workspaceId) {
            return memberships.values().stream()
                    .filter(m -> workspaceId.equals(m.workspaceId()))
                    .toList();
        }

        @Override
        public List<WorkspaceMembershipDto> listMembershipsByUserId(String userId) {
            return memberships.values().stream()
                    .filter(m -> userId.equals(m.userId()))
                    .toList();
        }

        @Override
        public int deleteMembership(String workspaceId, String userId) {
            String key = workspaceId + "_" + userId;
            return memberships.remove(key) != null ? 1 : 0;
        }

        @Override
        public synchronized int deleteMembershipIfNotLastOwner(String workspaceId, String userId) {
            String key = workspaceId + "_" + userId;
            WorkspaceMembershipDto target = memberships.get(key);
            if (target == null) return 0;
            if ("OWNER".equalsIgnoreCase(target.role())) {
                long activeOwners = memberships.values().stream()
                        .filter(m -> workspaceId.equals(m.workspaceId()))
                        .filter(m -> "OWNER".equalsIgnoreCase(m.role()))
                        .filter(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                        .count();
                if (activeOwners <= 1) return 0;
            }
            return memberships.remove(key, target) ? 1 : 0;
        }
    }

    static class InMemoryApiCredentialStore implements ApiCredentialStorePort {
        final Map<String, ApiCredentialDto> store = new ConcurrentHashMap<>();

        @Override
        public boolean save(ApiCredentialDto credential) {
            ApiCredentialDto existing = store.get(credential.credentialId());
            if (existing != null && existing.revision() != credential.revision()) {
                return false;
            }
            ApiCredentialDto toStore = existing == null
                    ? credential
                    : new ApiCredentialDto(
                            credential.credentialId(),
                            credential.userId(),
                            credential.keyDigest(),
                            credential.roleSet(),
                            credential.revision() + 1,
                            credential.expiresAt(),
                            credential.revokedAt(),
                            credential.createdAt(),
                            credential.updatedAt());
            store.put(credential.credentialId(), toStore);
            return true;
        }

        @Override
        public Optional<ApiCredentialDto> findById(String credentialId) {
            return Optional.ofNullable(store.get(credentialId));
        }

        @Override
        public Optional<ApiCredentialDto> findByKeyDigest(String keyDigest) {
            return store.values().stream()
                    .filter(c -> keyDigest.equals(c.keyDigest()))
                    .findFirst();
        }

        @Override
        public List<ApiCredentialDto> listByUserId(String userId) {
            return store.values().stream()
                    .filter(c -> userId.equals(c.userId()))
                    .toList();
        }

        @Override
        public int deleteById(String credentialId) {
            return store.remove(credentialId) != null ? 1 : 0;
        }
    }

    static class InMemoryWebSessionStore implements WebSessionStorePort {
        final Map<String, WebSessionDto> store = new ConcurrentHashMap<>();

        @Override
        public boolean save(WebSessionDto session) {
            WebSessionDto existing = store.get(session.sessionId());
            if (existing != null && existing.revision() != session.revision()) {
                return false;
            }
            WebSessionDto toStore = existing == null
                    ? session
                    : new WebSessionDto(
                            session.sessionId(),
                            session.userId(),
                            session.tokenDigest(),
                            session.csrfDigest(),
                            session.idleExpiresAt(),
                            session.absoluteExpiresAt(),
                            session.rotatedAt(),
                            session.revokedAt(),
                            session.safeClientMetadata(),
                            session.revision() + 1,
                            session.createdAt(),
                            session.updatedAt());
            store.put(session.sessionId(), toStore);
            return true;
        }

        @Override
        public Optional<WebSessionDto> findById(String sessionId) {
            return Optional.ofNullable(store.get(sessionId));
        }

        @Override
        public Optional<WebSessionDto> findByTokenDigest(String tokenDigest) {
            return store.values().stream()
                    .filter(s -> tokenDigest.equals(s.tokenDigest()))
                    .findFirst();
        }

        @Override
        public List<WebSessionDto> listByUserId(String userId) {
            return store.values().stream()
                    .filter(s -> userId.equals(s.userId()))
                    .toList();
        }

        @Override
        public int deleteById(String sessionId) {
            return store.remove(sessionId) != null ? 1 : 0;
        }

        @Override
        public int deleteByUserId(String userId) {
            List<String> toRemove = store.values().stream()
                    .filter(s -> userId.equals(s.userId()))
                    .map(WebSessionDto::sessionId)
                    .toList();
            toRemove.forEach(store::remove);
            return toRemove.size();
        }

        @Override
        public int deleteExpired(Instant now) {
            List<String> toRemove = store.values().stream()
                    .filter(s -> s.idleExpiresAt().isBefore(now)
                            || s.absoluteExpiresAt().isBefore(now))
                    .map(WebSessionDto::sessionId)
                    .toList();
            toRemove.forEach(store::remove);
            return toRemove.size();
        }
    }

    static class InMemoryIdentityInvitationStore implements IdentityInvitationStorePort {
        final Map<String, IdentityInvitationDto> store = new ConcurrentHashMap<>();

        @Override
        public boolean save(IdentityInvitationDto invitation) {
            IdentityInvitationDto existing = store.get(invitation.invitationId());
            if (existing != null && existing.revision() != invitation.revision()) {
                return false;
            }
            IdentityInvitationDto toStore = existing == null
                    ? invitation
                    : new IdentityInvitationDto(
                            invitation.invitationId(),
                            invitation.workspaceId(),
                            invitation.role(),
                            invitation.tokenDigest(),
                            invitation.optionalEmailConstraint(),
                            invitation.expiresAt(),
                            invitation.acceptedBy(),
                            invitation.revokedAt(),
                            invitation.revision() + 1,
                            invitation.createdAt(),
                            invitation.updatedAt());
            store.put(invitation.invitationId(), toStore);
            return true;
        }

        @Override
        public Optional<IdentityInvitationDto> findById(String invitationId) {
            return Optional.ofNullable(store.get(invitationId));
        }

        @Override
        public Optional<IdentityInvitationDto> findByTokenDigest(String tokenDigest) {
            return store.values().stream()
                    .filter(i -> tokenDigest.equals(i.tokenDigest()))
                    .findFirst();
        }

        @Override
        public List<IdentityInvitationDto> listByWorkspaceId(String workspaceId) {
            return store.values().stream()
                    .filter(i -> workspaceId.equals(i.workspaceId()))
                    .toList();
        }

        @Override
        public int deleteById(String invitationId) {
            return store.remove(invitationId) != null ? 1 : 0;
        }

        @Override
        public int deleteExpired(Instant now) {
            List<String> toRemove = store.values().stream()
                    .filter(i -> i.expiresAt().isBefore(now))
                    .map(IdentityInvitationDto::invitationId)
                    .toList();
            toRemove.forEach(store::remove);
            return toRemove.size();
        }
    }

    static class InMemoryBootstrapStateStore implements BootstrapStateStorePort {
        private static final String UNCLAIMED = "UNCLAIMED";
        private static final String CLAIMING = "CLAIMING";
        private static final String CREDENTIAL_ISSUED = "CREDENTIAL_ISSUED";
        private static final String CLAIMED = "CLAIMED";

        private BootstrapStateDto state;

        @Override
        public void save(BootstrapStateDto state) {
            if (this.state == null) {
                this.state = state;
            }
        }

        @Override
        public Optional<Integer> reserveFirstOwner(String ownerId, String workspaceId, Instant reservedAt) {
            if (state != null && !UNCLAIMED.equals(state.status())) {
                return Optional.empty();
            }
            int nextRevision = (state == null ? 0 : state.revision()) + 1;
            state = new BootstrapStateDto(
                    "bootstrap", CLAIMING, ownerId, workspaceId, null, null, null, nextRevision, reservedAt);
            return Optional.of(nextRevision);
        }

        @Override
        public boolean recordCredentialIssued(int expectedRevision, String credentialDigest, Instant issuedAt) {
            if (state == null || !CLAIMING.equals(state.status()) || state.revision() != expectedRevision) {
                return false;
            }
            state = new BootstrapStateDto(
                    "bootstrap",
                    CREDENTIAL_ISSUED,
                    state.ownerId(),
                    state.workspaceId(),
                    credentialDigest,
                    issuedAt,
                    null,
                    state.revision() + 1,
                    issuedAt);
            return true;
        }

        @Override
        public boolean confirmClaim(int expectedRevision, String authenticatedOwnerId, Instant claimedAt) {
            if (state == null
                    || !CREDENTIAL_ISSUED.equals(state.status())
                    || state.revision() != expectedRevision
                    || !authenticatedOwnerId.equals(state.ownerId())) {
                return false;
            }
            state = new BootstrapStateDto(
                    "bootstrap",
                    CLAIMED,
                    state.ownerId(),
                    state.workspaceId(),
                    state.credentialDigest(),
                    state.issuedAt(),
                    claimedAt,
                    state.revision() + 1,
                    claimedAt);
            return true;
        }

        @Override
        public boolean rollbackClaiming(int expectedRevision, Instant rolledBackAt) {
            if (state == null || !CLAIMING.equals(state.status()) || state.revision() != expectedRevision) {
                return false;
            }
            state = new BootstrapStateDto(
                    "bootstrap", UNCLAIMED, null, null, null, null, null, state.revision() + 1, rolledBackAt);
            return true;
        }

        @Override
        public boolean replaceCredential(int expectedRevision, String newDigest, Instant issuedAt, Instant updatedAt) {
            if (state == null
                    || (!CREDENTIAL_ISSUED.equals(state.status())
                            && !CLAIMING.equals(state.status())
                            && !CLAIMED.equals(state.status()))
                    || state.revision() != expectedRevision) {
                return false;
            }
            state = new BootstrapStateDto(
                    "bootstrap",
                    state.status(),
                    state.ownerId(),
                    state.workspaceId(),
                    newDigest,
                    issuedAt,
                    state.claimedAt(),
                    state.revision() + 1,
                    updatedAt);
            return true;
        }

        @Override
        public Optional<BootstrapStateDto> getState() {
            return Optional.ofNullable(state);
        }
    }
}
