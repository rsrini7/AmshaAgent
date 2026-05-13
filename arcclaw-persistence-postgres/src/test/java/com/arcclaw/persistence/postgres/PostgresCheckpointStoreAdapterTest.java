package com.arcclaw.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import com.arcclaw.persistence.api.CheckpointDto;
import com.arcclaw.persistence.api.CheckpointStorePort;
import com.arcclaw.persistence.api.ReasoningStepDto;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
@EnabledIf("com.arcclaw.persistence.postgres.DockerAvailableCondition#isDockerAvailable")
class PostgresCheckpointStoreAdapterTest {

    @Inject
    CheckpointStorePort store;

    @Test
    void testSaveAndFindById() {
        CheckpointDto dto = createCheckpoint("cpt-save-1", "IN_PROGRESS");
        CheckpointDto saved = store.save(dto);
        assertNotNull(saved.id());

        Optional<CheckpointDto> found = store.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("cpt-save-1", found.get().sessionId());
        assertEquals("IN_PROGRESS", found.get().status());
    }

    @Test
    void testTransitionStatus() {
        CheckpointDto saved = store.save(createCheckpoint("cpt-trn-1", "IN_PROGRESS"));
        store.transitionStatus(saved.id(), "COMPLETED");
        Optional<CheckpointDto> found = store.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("COMPLETED", found.get().status());
    }

    @Test
    void testAtomicTransition() {
        CheckpointDto saved = store.save(createCheckpoint("cpt-atomic-1", "AWAITING_APPROVAL"));
        assertTrue(store.atomicTransition(saved.id(), "AWAITING_APPROVAL", "APPROVED"));
        assertEquals("APPROVED", store.findById(saved.id()).get().status());
    }

    @Test
    void testAtomicTransitionWrongStatus() {
        CheckpointDto saved = store.save(createCheckpoint("cpt-atomic-2", "COMPLETED"));
        assertFalse(store.atomicTransition(saved.id(), "AWAITING_APPROVAL", "APPROVED"));
    }

    @Test
    void testSaveCheckpointWithSteps() {
        CheckpointDto dto = createCheckpoint("cpt-steps-1", "IN_PROGRESS");
        List<ReasoningStepDto> steps = List.of(
            new ReasoningStepDto("thought-1", "action-1", "{}", "obs-1", 100, 50, true, null, null, null),
            new ReasoningStepDto("thought-2", "action-2", "{}", "obs-2", 200, 100, true, null, null, null)
        );
        CheckpointDto saved = store.saveCheckpointWithSteps(dto, steps);
        List<ReasoningStepDto> loaded = store.loadSteps(saved.id());
        assertEquals(2, loaded.size());
    }

    @Test
    void testFindActiveBySession() {
        store.save(createCheckpoint("cpt-active-1", "IN_PROGRESS"));
        store.save(createCheckpoint("cpt-active-1", "IN_PROGRESS"));
        store.save(createCheckpoint("cpt-active-1", "COMPLETED"));
        assertEquals(2, store.findActiveBySession("cpt-active-1").size());
    }

    @Test
    void testFindByStatus() {
        store.save(createCheckpoint("cpt-status-1", "AWAITING_APPROVAL"));
        store.save(createCheckpoint("cpt-status-1", "IN_PROGRESS"));
        List<CheckpointDto> awaiting = store.findByStatus("AWAITING_APPROVAL");
        assertEquals(1, awaiting.size());
    }

    @Test
    void testFindBySessionAndRole() {
        store.save(new CheckpointDto(null, "cpt-sr-1", "primary", "IN_PROGRESS", "FULL", "{}",
            "plat-1", "telegram", "goal", null, null, null, null, null, null, null,
            null, 0, null, null, null, null, null, null, Instant.now(), Instant.now()));
        Optional<CheckpointDto> found = store.findBySessionAndRole("cpt-sr-1", "primary");
        assertTrue(found.isPresent());
    }

    @Test
    void testMarkFailed() {
        CheckpointDto saved = store.save(createCheckpoint("cpt-fail-1", "IN_PROGRESS"));
        store.markFailed(saved.id(), "test failure", "ERR_001", "trace-1", "TestException");
        Optional<CheckpointDto> found = store.findById(saved.id());
        assertTrue(found.isPresent());
        assertEquals("FAILED", found.get().status());
    }

    @Test
    void testHasPendingApproval() {
        store.save(createCheckpoint("cpt-approve-1", "AWAITING_APPROVAL"));
        assertTrue(store.hasPendingApproval("cpt-approve-1"));
        store.save(createCheckpoint("cpt-approve-1", "COMPLETED"));
        assertTrue(store.hasPendingApproval("cpt-approve-1"));
    }

    @Test
    void testFindStaleApprovals() {
        store.save(createCheckpoint("cpt-stale-1", "AWAITING_APPROVAL"));
        List<CheckpointDto> stale = store.findStaleApprovals(Instant.now().plusSeconds(3600));
        assertTrue(stale.size() >= 1);
    }

    private CheckpointDto createCheckpoint(String sessionId, String status) {
        return new CheckpointDto(null, sessionId, "role", status, "FULL", "{}",
            "plat-1", "telegram", "goal", null, null, null, null, null, null, null,
            null, 0, null, null, null, null, null, null, Instant.now(), Instant.now());
    }
}
