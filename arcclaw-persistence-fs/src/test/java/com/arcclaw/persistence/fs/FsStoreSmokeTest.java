package com.arcclaw.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.arcclaw.persistence.api.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"java:S1192", "java:S5443"}) // Test-only: temp dirs in test are acceptable
class FsStoreSmokeTest {

    private Path tempDir;
    private FilesystemFileStore fileStore;
    private FsSkillStoreAdapter skillStore;
    private FsChatMemoryStoreAdapter chatMemoryStore;
    private FsSecretStoreAdapter secretStore;
    private FsCompactionStoreAdapter compactionStore;
    private FsCronStoreAdapter cronStore;
    private FsCheckpointStoreAdapter checkpointStore;
    private FsLockManager lockManager;
    private FsAuditEventStoreAdapter auditStore;
    private FsMemoryRetrievalAdapter memoryStore;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("arcclaw-fs-test-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();

        skillStore = new FsSkillStoreAdapter();
        setField(skillStore, "store", fileStore);

        chatMemoryStore = new FsChatMemoryStoreAdapter();
        setField(chatMemoryStore, "store", fileStore);

        secretStore = new FsSecretStoreAdapter();
        setField(secretStore, "store", fileStore);

        compactionStore = new FsCompactionStoreAdapter();
        setField(compactionStore, "store", fileStore);

        lockManager = new FsLockManager(props);
        lockManager.ensureLocksDir();

        cronStore = new FsCronStoreAdapter();
        setField(cronStore, "store", fileStore);

        checkpointStore = new FsCheckpointStoreAdapter();
        setField(checkpointStore, "store", fileStore);
        FsCheckpointHelper helper = new FsCheckpointHelper();
        setField(helper, "store", fileStore);
        helper.initIdSeq();
        setField(checkpointStore, "helper", helper);

        auditStore = new FsAuditEventStoreAdapter();
        setField(auditStore, "store", fileStore);

        memoryStore = new FsMemoryRetrievalAdapter();
        setField(memoryStore, "store", fileStore);
        setField(memoryStore, "properties", props);
        setField(memoryStore, "objectMapper", fileStore.objectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    /* best-effort cleanup */
                }
            });
        }
    }

    // ─── Skills ────────────────────────────────────────────────

    @Test
    void testSkillStore() {
        SkillDto dto = new SkillDto("s1", "TestSkill", "yaml: hello", Instant.now(), Instant.now());
        skillStore.save(dto);
        assertEquals(1, skillStore.findAll().size());
        assertTrue(skillStore.findByName("TestSkill").isPresent());
        assertTrue(skillStore.deleteById("s1"));
        assertTrue(skillStore.findAll().isEmpty());
    }

    @Test
    void testSkillDeleteAll() {
        skillStore.save(new SkillDto("a", "A", "yaml", Instant.now(), Instant.now()));
        skillStore.save(new SkillDto("b", "B", "yaml", Instant.now(), Instant.now()));
        assertEquals(2, skillStore.deleteAll());
        assertTrue(skillStore.findAll().isEmpty());
    }

    // ─── Chat Memory ───────────────────────────────────────────

    @Test
    void testChatMemoryStore() {
        ChatMemoryDto dto = new ChatMemoryDto("mem1", "[{}]", "{}");
        chatMemoryStore.save(dto);
        assertTrue(chatMemoryStore.findById("mem1").isPresent());
        chatMemoryStore.deleteById("mem1");
        assertTrue(chatMemoryStore.findById("mem1").isEmpty());
    }

    // ─── Secrets ───────────────────────────────────────────────

    @Test
    void testSecretStore() {
        SecretDto dto = new SecretDto("session1", "key1", 1, new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
        secretStore.save(dto);
        assertTrue(secretStore.find("session1", "key1").isPresent());
        assertEquals(1, secretStore.find("session1", "key1").get().encryptionKeyVersion());
        secretStore.delete("session1", "key1");
        assertTrue(secretStore.find("session1", "key1").isEmpty());
    }

    @Test
    void testSecretDeleteBySession() {
        secretStore.save(new SecretDto("sess", "k1", 1, new byte[] {1}, new byte[] {2}));
        secretStore.save(new SecretDto("sess", "k2", 1, new byte[] {3}, new byte[] {4}));
        secretStore.deleteBySessionId("sess");
        assertTrue(secretStore.find("sess", "k1").isEmpty());
        assertTrue(secretStore.find("sess", "k2").isEmpty());
    }

    // ─── Compaction ────────────────────────────────────────────

    @Test
    void testCompactionStore() {
        CompactionSummaryDto dto = new CompactionSummaryDto("sess1", "summary text", Instant.now(), 5, "user1");
        compactionStore.upsert(dto);
        assertTrue(compactionStore.findLatestBySessionId("sess1").isPresent());
        assertEquals(1, compactionStore.search("user1", "summary", 10).size());
        compactionStore.deleteBySessionId("sess1");
        assertTrue(compactionStore.findLatestBySessionId("sess1").isEmpty());
    }

    // ─── Lock Manager ──────────────────────────────────────────

    @Test
    void testLockManager() {
        assertTrue(lockManager.tryLock("test-lock", 10));
        assertTrue(lockManager.isLocked("test-lock"));
        assertTrue(lockManager.tryLock("test-lock", 10));
        lockManager.unlock("test-lock");
        assertFalse(lockManager.isLocked("test-lock"));
    }

    // ─── Cron ──────────────────────────────────────────────────

    @Test
    void testCronStore() {
        String id = UUID.randomUUID().toString();
        AgentCronDto dto = new AgentCronDto(
                id,
                "sess",
                "user1",
                "chat1",
                "telegram",
                "123",
                "0 * * * *",
                "UTC",
                false,
                "do thing",
                Instant.now().minusSeconds(60),
                Instant.now(),
                "ACTIVE",
                "role1",
                null,
                null,
                Instant.now(),
                Instant.now());
        cronStore.save(dto);
        assertTrue(cronStore.findById(id).isPresent());
        assertFalse(cronStore.findDueCrons().isEmpty());
        assertFalse(cronStore.findByUserId("user1").isEmpty());

        assertTrue(cronStore.claimForFire(id));
        Optional<AgentCronDto> running = cronStore.findById(id);
        assertTrue(running.isPresent());
        assertEquals("RUNNING", running.get().status());

        cronStore.markCompleted(id, Instant.now());
        Optional<AgentCronDto> completed = cronStore.findById(id);
        assertTrue(completed.isPresent());
        assertEquals("COMPLETED", completed.get().status());
    }

    @Test
    void testCronLifecycle() {
        String id = UUID.randomUUID().toString();
        AgentCronDto dto = new AgentCronDto(
                id,
                "sess",
                "user1",
                null,
                null,
                null,
                "*/5 * * * *",
                "UTC",
                false,
                "task",
                Instant.now().minusSeconds(30),
                null,
                "ACTIVE",
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
        cronStore.save(dto);

        assertTrue(cronStore.claimForFire(id));
        cronStore.releaseClaimToActive(id);
        assertEquals("ACTIVE", cronStore.findById(id).get().status());
        assertTrue(cronStore.claimForFire(id));
        cronStore.markAwaitingApproval(id);
        assertEquals("AWAITING_APPROVAL", cronStore.findById(id).get().status());
    }

    // ─── Checkpoint ────────────────────────────────────────────

    @Test
    void testCheckpointSaveAndFind() {
        CheckpointDto cp = checkpointStore.save(new CheckpointDto(
                null,
                "session-cp-1",
                "primary",
                "IN_PROGRESS",
                "LIMITED",
                "{}",
                "platform1",
                "slack",
                "goal1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null));
        assertNotNull(cp.id());
        assertTrue(checkpointStore.findById(cp.id()).isPresent());
        assertTrue(
                checkpointStore.findBySessionAndRole("session-cp-1", "primary").isPresent());
    }

    @Test
    void testCheckpointStatusTransition() {
        CheckpointDto cp = checkpointStore.save(new CheckpointDto(
                null,
                "session-cp-2",
                "primary",
                "IN_PROGRESS",
                "LIMITED",
                "{}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null));
        checkpointStore.transitionStatus(cp.id(), "COMPLETED");
        Optional<CheckpointDto> found = checkpointStore.findById(cp.id());
        assertTrue(found.isPresent());
        assertEquals("COMPLETED", found.get().status());
    }

    @Test
    void testCheckpointAtomicTransition() {
        CheckpointDto cp = checkpointStore.save(new CheckpointDto(
                null,
                "session-cp-3",
                "primary",
                "IN_PROGRESS",
                "LIMITED",
                "{}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null));
        assertTrue(checkpointStore.atomicTransition(cp.id(), "IN_PROGRESS", "COMPLETED"));
        assertFalse(checkpointStore.atomicTransition(cp.id(), "IN_PROGRESS", "FAILED"));
    }

    @Test
    void testCheckpointSteps() {
        CheckpointDto cp = checkpointStore.save(new CheckpointDto(
                null,
                "session-cp-steps",
                "primary",
                "IN_PROGRESS",
                "LIMITED",
                "{}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null));
        var step1 = new ReasoningStepDto("{\"thought\":\"step1\"}", "read", "{}", "ok", 10, 20, true, null, null, null);
        var step2 =
                new ReasoningStepDto("{\"thought\":\"step2\"}", "write", "{}", "done", 5, 10, true, null, null, null);
        checkpointStore.saveSteps(cp.id(), List.of(step1, step2));
        assertEquals(2, checkpointStore.loadSteps(cp.id()).size());
        checkpointStore.deleteSteps(cp.id());
        assertTrue(checkpointStore.loadSteps(cp.id()).isEmpty());
    }

    @Test
    void testCheckpointUpsert() {
        CheckpointUpsertResult result =
                checkpointStore.upsertWithSteps("session-upsert", "primary", "{}", "LIMITED", 0, "goal", "{}");
        assertTrue(result.created());
        assertNotNull(result.checkpoint().id());

        CheckpointUpsertResult result2 = checkpointStore.upsertWithSteps(
                "session-upsert", "primary", "{\"updated\":true}", "LIMITED", 1, null, null);
        assertFalse(result2.created());
    }

    @Test
    void testCheckpointMarkFailed() {
        CheckpointDto cp = checkpointStore.save(new CheckpointDto(
                null,
                "session-fail",
                "primary",
                "IN_PROGRESS",
                "LIMITED",
                "{}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null));
        checkpointStore.markFailed(cp.id(), "test error", "ERR001", "trace1", "TestException");
        Optional<CheckpointDto> found = checkpointStore.findById(cp.id());
        assertTrue(found.isPresent());
        assertEquals("FAILED", found.get().status());
        assertEquals("test error", found.get().failReason());
    }

    // ─── Audit ─────────────────────────────────────────────────

    @Test
    void testAuditStore() {
        String session = "audit-session-1";
        UUID id1 = UUID.randomUUID();
        AuditEventDto event1 = new AuditEventDto(
                id1,
                session,
                "user1",
                "agent1",
                "hash1",
                "SUCCESS",
                "TOOL_USE",
                "detail1",
                100,
                50,
                null,
                Instant.now(),
                1,
                "1.0",
                "ikey1",
                1L,
                "hmac1",
                null);
        auditStore.save(event1);

        assertEquals(1, auditStore.findBySessionId(session).size());
        assertEquals(1, auditStore.getMaxSequence(session));

        UUID id2 = UUID.randomUUID();
        AuditEventDto event2 = new AuditEventDto(
                id2,
                session,
                "user1",
                "agent1",
                "hash2",
                "SUCCESS",
                "SESSION_START",
                "detail2",
                200,
                100,
                null,
                Instant.now(),
                2,
                "1.0",
                "ikey2",
                2L,
                "hmac2",
                "hmac1");
        auditStore.save(event2);

        assertEquals(2, auditStore.findBySessionId(session).size());
        assertEquals(1, auditStore.countEventsByType(session, "TOOL_USE"));
        assertEquals(1, auditStore.countEventsByType(session, "SESSION_START"));
    }

    // ─── Memory Retrieval ──────────────────────────────────────

    @Test
    void testMemoryRetrieval() {
        memoryStore.init();
        IngestResult result = memoryStore.index(new MemoryEntryDto("mem1", "hello world", Map.of()));
        assertNotNull(result.id());
        assertEquals(1, memoryStore.search("hello", 10, null).size());
        assertEquals(0, memoryStore.search("goodbye", 10, null).size());
    }

    // ─── PersistenceAdapter ────────────────────────────────────

    @Test
    void testPersistenceAdapter() throws Exception {
        FsPersistenceAdapter adapter = new FsPersistenceAdapter();
        setField(adapter, "store", fileStore);
        setField(adapter, "lockManager", lockManager);
        adapter.execute("test-session", "test-op", () -> {
            skillStore.save(new SkillDto("pk-test", "PersistenceTest", "yaml", Instant.now(), Instant.now()));
        });
        assertTrue(skillStore.findByName("PersistenceTest").isPresent());
    }

    // ─── Audit Schema Enforcer ─────────────────────────────────

    @Test
    void testAuditSchemaEnforcer() throws Exception {
        FsAuditSchemaEnforcer enforcer = new FsAuditSchemaEnforcer();
        setField(enforcer, "store", fileStore);
        assertDoesNotThrow(() -> enforcer.enforceWormConstraints());
    }

    // ─── Helpers ───────────────────────────────────────────────

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            if (clazz.getSuperclass() != null) return findField(clazz.getSuperclass(), fieldName);
            throw e;
        }
    }
}
