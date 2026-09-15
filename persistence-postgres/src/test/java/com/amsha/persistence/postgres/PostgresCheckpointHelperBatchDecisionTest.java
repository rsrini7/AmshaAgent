package com.amsha.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit coverage for {@link PostgresCheckpointHelper#mergeBatchDecisions}, the best-effort merge that
 * lets a resume inherit batch tool decisions recorded on the managed checkpoint.
 *
 * <p>Why this class exists: {@link PostgresCheckpointStoreAdapterEdgeCaseTest} drives the helper through a
 * live database, which covers the happy merge but can never reach its fallback arms — the two JSON failure
 * paths and the "nothing to inherit" ternary. Those are exactly the arms whose whole purpose is to keep a
 * resume working when the stored JSON is unreadable or unwritable, so leaving them dark means leaving the
 * recovery behaviour unverified.
 *
 * <p>The assertion that matters in all three fallback tests is {@code assertSame}: the helper must hand the
 * caller back <em>its own</em> DTO, not a partially-merged copy, because the caller goes on to persist it.
 */
class PostgresCheckpointHelperBatchDecisionTest {

    private static final String MANAGED_JSON = "{\"k1\":\"APPROVED\"}";
    private static final String INCOMING_JSON = "{\"k1\":\"PENDING\"}";

    private static CheckpointDto checkpoint(String sessionId, String batchToolDecisions) {
        Instant now = Instant.now();
        return new CheckpointDto(
                null,
                sessionId,
                "coder",
                "RUNNING",
                "FULL",
                null,
                null,
                null,
                "goal",
                null,
                null,
                null,
                null,
                batchToolDecisions,
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
                now,
                now);
    }

    // =========================================================================
    // The "nothing to merge" arms
    // =========================================================================

    @Test
    @DisplayName("an incoming checkpoint with no decisions inherits the managed ones")
    void incomingWithoutDecisionsInheritsTheManagedOnes() {
        CheckpointDto merged = PostgresCheckpointHelper.mergeBatchDecisions(
                checkpoint("sess-1", MANAGED_JSON), checkpoint("sess-1", null), new ObjectMapper());

        assertEquals(MANAGED_JSON, merged.batchToolDecisions());
    }

    @Test
    @DisplayName("an incoming checkpoint with no decisions is returned as-is when the managed one has none")
    void incomingWithoutDecisionsIsUntouchedWhenThereIsNothingToInherit() {
        CheckpointDto incoming = checkpoint("sess-1", null);

        CheckpointDto merged =
                PostgresCheckpointHelper.mergeBatchDecisions(checkpoint("sess-1", null), incoming, new ObjectMapper());

        assertSame(incoming, merged);
        assertNull(merged.batchToolDecisions());
    }

    @Test
    @DisplayName("a managed checkpoint with no decisions leaves the incoming ones alone")
    void managedWithoutDecisionsLeavesIncomingAlone() {
        CheckpointDto incoming = checkpoint("sess-1", INCOMING_JSON);

        CheckpointDto merged =
                PostgresCheckpointHelper.mergeBatchDecisions(checkpoint("sess-1", null), incoming, new ObjectMapper());

        assertSame(incoming, merged);
    }

    // =========================================================================
    // The real merge
    // =========================================================================

    @Test
    @DisplayName("a managed decision replaces a still-pending incoming one")
    void aManagedDecisionReplacesAPendingIncomingOne() {
        CheckpointDto merged = PostgresCheckpointHelper.mergeBatchDecisions(
                checkpoint("sess-1", MANAGED_JSON), checkpoint("sess-1", INCOMING_JSON), new ObjectMapper());

        assertTrue(
                merged.batchToolDecisions().contains("APPROVED"),
                "the already-decided managed value must win over the replayed PENDING one, but got: "
                        + merged.batchToolDecisions());
    }

    // =========================================================================
    // The failure arms
    // =========================================================================

    @Test
    @DisplayName("an unreadable decision column falls back to the incoming value")
    void anUnreadableColumnFallsBackToTheIncomingValue() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        doThrow(new IllegalStateException("not json")).when(mapper).readValue(anyString(), any(TypeReference.class));
        CheckpointDto incoming = checkpoint("sess-1", INCOMING_JSON);

        CheckpointDto merged =
                PostgresCheckpointHelper.mergeBatchDecisions(checkpoint("sess-1", MANAGED_JSON), incoming, mapper);

        assertSame(incoming, merged, "a resume must not fail because the stored decisions cannot be parsed");
    }

    @Test
    @DisplayName("a serialization failure after a real merge falls back to the incoming value")
    void aSerializationFailureFallsBackToTheIncomingValue() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        // Mutable maps, because the merge path mutates the parsed incoming map in place.
        doReturn(new HashMap<>(Map.of("k1", ToolDecision.APPROVED)))
                .when(mapper)
                .readValue(eq(MANAGED_JSON), any(TypeReference.class));
        doReturn(new HashMap<>(Map.of("k1", ToolDecision.PENDING)))
                .when(mapper)
                .readValue(eq(INCOMING_JSON), any(TypeReference.class));
        doThrow(new IllegalStateException("cannot serialize")).when(mapper).writeValueAsString(any());
        CheckpointDto incoming = checkpoint("sess-1", INCOMING_JSON);

        CheckpointDto merged =
                PostgresCheckpointHelper.mergeBatchDecisions(checkpoint("sess-1", MANAGED_JSON), incoming, mapper);

        assertSame(incoming, merged, "the merge is best-effort; an unwritable result must not be lost");
    }
}
