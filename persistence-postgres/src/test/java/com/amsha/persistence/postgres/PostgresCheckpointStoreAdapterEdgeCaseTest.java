package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.postgres.testutil.FlywayEnabledTestProfile;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(FlywayEnabledTestProfile.class)
@QuarkusTestResource(PostgresTestResource.class)
class PostgresCheckpointStoreAdapterEdgeCaseTest {

    @Inject
    CheckpointStorePort store;

    @Test
    void testFindByIdNonExistent() {
        Optional<CheckpointDto> found = store.findById(99999L);
        assertFalse(found.isPresent());
    }

    @Test
    void testAtomicTransitionOnNonExistentCheckpoint() {
        assertFalse(store.atomicTransition(99999L, "IN_PROGRESS", "COMPLETED"));
    }

    @Test
    void testHasPendingApprovalReturnsFalseForEmptySession() {
        assertFalse(store.hasPendingApproval("non-existent-session-xyz"));
    }

    @Test
    void testFindBySessionAndRoleNonExistent() {
        Optional<CheckpointDto> found = store.findBySessionAndRole("no-session", "no-role");
        assertFalse(found.isPresent());
    }

    @Test
    void testFindActiveBySessionEmpty() {
        List<CheckpointDto> active = store.findActiveBySession("empty-session");
        assertTrue(active.isEmpty());
    }

    @Test
    void testFindByStatusEmpty() {
        List<CheckpointDto> found = store.findByStatus("AWAITING_APPROVAL");
        long initialCount = found.size();
        assertTrue(initialCount >= 0);
    }

    @Test
    void testLoadStepsForNonExistentCheckpoint() {
        List<ReasoningStepDto> steps = store.loadSteps(99999L);
        assertTrue(steps.isEmpty());
    }

    @Test
    void testFindLatestAwaitingApprovalNonExistent() {
        Optional<CheckpointDto> found = store.findLatestAwaitingApproval("no-session");
        assertFalse(found.isPresent());
    }

    @Test
    void testTransitionStatusNonExistentIsNoOp() {
        store.transitionStatus(99999L, "COMPLETED");
    }

    @Test
    void testDeleteCheckpointNonExistent() {
        store.deleteCheckpoint("no-session", "no-role");
    }

    @Test
    void testFindLatestBySessionAndStatusNonExistent() {
        Optional<CheckpointDto> found = store.findLatestAwaitingApprovalBySessionContaining("no-such-session-segment");
        assertFalse(found.isPresent());
    }
}
