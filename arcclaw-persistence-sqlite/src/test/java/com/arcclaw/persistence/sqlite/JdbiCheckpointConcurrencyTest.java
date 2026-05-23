package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;

import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import io.quarkus.test.junit.QuarkusTest;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class JdbiCheckpointConcurrencyTest {

    @Inject
    CheckpointStorePort checkpointStore;

    @Inject
    Jdbi jdbi;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "conc-test-" + UUID.randomUUID().toString().substring(0, 8);
        // Ensure clean state
        jdbi.useHandle(handle -> handle.createUpdate(
                "DELETE FROM reasoning_step WHERE checkpoint_id IN (SELECT checkpoint_id FROM agent_checkpoint WHERE session_id = :sid)")
                .bind("sid", sessionId).execute());
        jdbi.useHandle(handle -> handle.createUpdate("DELETE FROM agent_checkpoint WHERE session_id = :sid")
                .bind("sid", sessionId).execute());
    }

    @Test
    void concurrentUpsertsDoNotCorruptCheckpoint() throws Exception {
        CheckpointDto initial = new CheckpointDto(
                null, sessionId, "coordinator", "IN_PROGRESS", "FULL", "{}",
                null, null, "test goal", null, null, null, null, null, null, null,
                null, 0, null, null, null, null, null, null,
                null, 0, Instant.now(), Instant.now());
        checkpointStore.save(initial);

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    CheckpointDto dto = new CheckpointDto(
                            null, sessionId, "coordinator", "IN_PROGRESS", "FULL",
                            "{\"thread\":" + idx + "}",
                            null, null, "test goal", null, null, null, null, null, null, null,
                            null, idx, null, null, null, null, null, null,
                            null, 0, Instant.now(), Instant.now());
                    checkpointStore.save(dto);
                } catch (Exception e) {
                    error.compareAndSet(null, e);
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertNull(error.get(), "Concurrent upsert should not throw: " + error.get());

        CheckpointDto finalState = checkpointStore.findBySessionAndRole(sessionId, "coordinator").orElseThrow();
        assertNotNull(finalState.status());
    }

    @Test
    void atomicUpdateBatchDecisionPreservesNonStaleDecisions() throws Exception {
        CheckpointDto initial = new CheckpointDto(
                null, sessionId, "coordinator", "AWAITING_APPROVAL", "FULL", "{}",
                null, null, "test goal", null, null, null, null,
                "{\"tool_a\":\"PENDING\",\"tool_b\":\"PENDING\"}", null, null,
                null, 0, null, null, null, null, null, null,
                null, 0, Instant.now(), Instant.now());
        checkpointStore.save(initial);

        // Update a single tool decision
        boolean updated = checkpointStore.atomicUpdateBatchToolDecision(
                sessionId, "coordinator", "tool_a", "APPROVED");
        assertTrue(updated, "Batch tool decision should be updated");

        // Save with AWAITING_APPROVAL status but null batch decisions — merge gate
        // should preserve them
        CheckpointDto saveWithoutDecisions = new CheckpointDto(
                null, sessionId, "coordinator", "AWAITING_APPROVAL", "FULL", "{}",
                null, null, "test goal", null, null, null, null, null,
                null, null, // batchToolDecisions explicitly null
                null, 0, null, null, null, null, null, null,
                null, 0, Instant.now(), Instant.now());
        checkpointStore.save(saveWithoutDecisions);

        // Batch decisions should still contain the approved decision from the atomic
        // update
        CheckpointDto finalState = checkpointStore.findBySessionAndRole(sessionId, "coordinator").orElseThrow();
        String decisions = finalState.batchToolDecisions();
        assertNotNull(decisions, "Batch decisions should not be lost");
        assertTrue(decisions.contains("APPROVED"), "Approved decision should survive");
        assertTrue(decisions.contains("PENDING"), "Other decisions should remain");
    }
}
