package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.ToolDecision;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link SqliteCheckpointHelper#mergeBatchDecisions} — the batch-tool-decision
 * merge used by {@link SqliteCheckpointStoreAdapter#upsert} when a checkpoint already exists.
 *
 * <p>The existing {@code JdbiCheckpointConcurrencyTest.mergeBatchDecisionsPreservesManagedValueWhenIncomingNull}
 * exercises only the single "incoming has no decisions, managed does" arm through the full adapter.
 * Every other arm — the no-change fallthrough, the resolved-over-pending merge, and both
 * exception handlers — was uncovered, which is 22 of the helper's 42 executable lines.
 *
 * <p>Plain unit test: the method is pure, so a real {@link ObjectMapper} plus hand-built
 * {@link CheckpointDto}s is enough and no database is involved.
 */
class SqliteCheckpointHelperMergeTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void incomingWithoutDecisionsAndManagedWithoutDecisionsReturnsIncoming() {
        CheckpointDto incoming = checkpoint("s1", null);

        // Neither side has decisions: there is nothing to preserve, so `incoming` is returned as-is.
        assertSame(
                incoming, SqliteCheckpointHelper.mergeBatchDecisions(checkpoint("s1", null), incoming, objectMapper));
    }

    @Test
    void incomingWithoutDecisionsInheritsTheManagedDecisions() {
        CheckpointDto managed = checkpoint("s1", "{\"toolA\":\"APPROVED\"}");
        CheckpointDto incoming = checkpoint("s1", null);

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(managed, incoming, objectMapper);

        assertEquals("{\"toolA\":\"APPROVED\"}", merged.batchToolDecisions());
        assertNotSame(incoming, merged);
    }

    @Test
    void managedWithoutDecisionsReturnsIncomingUnchanged() {
        CheckpointDto incoming = checkpoint("s1", "{\"toolA\":\"PENDING\"}");

        // Nothing on the managed side to merge in, so incoming wins outright.
        assertSame(
                incoming, SqliteCheckpointHelper.mergeBatchDecisions(checkpoint("s1", null), incoming, objectMapper));
    }

    @Test
    void aResolvedManagedDecisionOverridesAPendingIncomingOne() throws Exception {
        CheckpointDto managed = checkpoint("s1", "{\"toolA\":\"APPROVED\"}");
        CheckpointDto incoming = checkpoint("s1", "{\"toolA\":\"PENDING\"}");

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(managed, incoming, objectMapper);

        assertNotSame(incoming, merged);
        assertEquals(Map.of("toolA", ToolDecision.APPROVED), decisions(merged.batchToolDecisions()));
    }

    @Test
    void aDeniedManagedDecisionAlsoOverrides() throws Exception {
        CheckpointDto managed = checkpoint("s1", "{\"toolA\":\"DENIED\"}");
        CheckpointDto incoming = checkpoint("s1", "{\"toolA\":\"PENDING\"}");

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(managed, incoming, objectMapper);

        assertEquals(Map.of("toolA", ToolDecision.DENIED), decisions(merged.batchToolDecisions()));
    }

    @Test
    void nothingChangesWhenTheManagedDecisionIsStillPending() throws Exception {
        CheckpointDto incoming = checkpoint("s1", "{\"toolA\":\"PENDING\"}");

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(
                checkpoint("s1", "{\"toolA\":\"PENDING\"}"), incoming, objectMapper);

        // No value to carry over, so the merge is a no-op and `incoming` is returned untouched.
        assertSame(incoming, merged);
    }

    @Test
    void aManagedKeyMissingFromIncomingIsIgnored() throws Exception {
        CheckpointDto incoming = checkpoint("s1", "{\"toolB\":\"PENDING\"}");

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(
                checkpoint("s1", "{\"toolA\":\"APPROVED\"}"), incoming, objectMapper);

        assertSame(incoming, merged);
        assertEquals(Map.of("toolB", ToolDecision.PENDING), decisions(merged.batchToolDecisions()));
    }

    @Test
    void malformedManagedJsonIsSwallowedAndIncomingIsReturned() throws Exception {
        CheckpointDto incoming = checkpoint("s1", "{\"toolA\":\"PENDING\"}");

        // Not JSON at all: the outer catch must absorb it rather than failing the checkpoint write.
        CheckpointDto merged =
                SqliteCheckpointHelper.mergeBatchDecisions(checkpoint("s1", "not-json"), incoming, objectMapper);

        assertSame(incoming, merged);
        assertEquals(Map.of("toolA", ToolDecision.PENDING), decisions(merged.batchToolDecisions()));
    }

    @Test
    void malformedIncomingJsonIsAlsoSwallowed() throws Exception {
        CheckpointDto incoming = checkpoint("s1", "not-json");

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(
                checkpoint("s1", "{\"toolA\":\"APPROVED\"}"), incoming, objectMapper);

        assertSame(incoming, merged);
    }

    @Test
    void aSerializationFailureIsSwallowedAndIncomingIsReturned() throws Exception {
        CheckpointDto incoming = checkpoint("s1", "{\"toolA\":\"PENDING\"}");

        // Delegate reads to a real mapper but force the write to fail, so only the inner catch runs.
        ObjectMapper real = new ObjectMapper();
        ObjectMapper failingWriter = mock(ObjectMapper.class);
        when(failingWriter.readValue(anyString(), any(TypeReference.class))).thenAnswer(invocation -> {
            String json = invocation.getArgument(0);
            return real.readValue(json, (TypeReference<?>) invocation.getArgument(1));
        });
        when(failingWriter.writeValueAsString(any())).thenThrow(new IllegalStateException("cannot serialize"));

        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(
                checkpoint("s1", "{\"toolA\":\"APPROVED\"}"), incoming, failingWriter);

        assertSame(incoming, merged, "a failed write must degrade to the unmerged incoming checkpoint");
    }

    @Test
    void theMergeIsScopedToTheCheckpointsOwnSession() throws Exception {
        // The merge is keyed purely on the decision map, so a differing sessionId still merges.
        CheckpointDto merged = SqliteCheckpointHelper.mergeBatchDecisions(
                checkpoint("managed-session", "{\"toolA\":\"APPROVED\"}"),
                checkpoint("incoming-session", "{\"toolA\":\"PENDING\"}"),
                objectMapper);

        assertEquals("incoming-session", merged.sessionId());
        assertEquals(Map.of("toolA", ToolDecision.APPROVED), decisions(merged.batchToolDecisions()));
        assertTrue(merged.batchToolDecisions().contains("APPROVED"));
    }

    // ---------- helpers ----------

    private static Map<String, ToolDecision> decisions(String json) throws Exception {
        return new ObjectMapper().readValue(json, new TypeReference<Map<String, ToolDecision>>() {});
    }

    private static CheckpointDto checkpoint(String sessionId, String batchToolDecisions) {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        return new CheckpointDto(
                null,
                sessionId,
                "coordinator",
                "IN_PROGRESS",
                "FULL",
                "{}",
                "chat-1",
                "CLI",
                "goal",
                null,
                null,
                null,
                null,
                batchToolDecisions,
                null,
                null,
                10,
                1,
                null,
                null,
                "{}",
                null,
                null,
                null,
                null,
                0,
                t,
                t);
    }
}
