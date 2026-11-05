package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;

import org.junit.jupiter.api.Test;

class PendingToolOperationIdLookupTest {
    private static final String FIRST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECOND_ID = "123e4567-e89b-12d3-a456-426614174001";

    @Test
    void returnsMostRecentMatchingUnresolvedCall() {
        ReasoningStep first = pending("file_read", Map.of("path", "one.txt"), FIRST_ID);
        ReasoningStep completed =
                pending("file_read", Map.of("path", "one.txt"), FIRST_ID).withObservation("done", true);
        ReasoningStep second = pending("file_read", Map.of("path", "two.txt"), SECOND_ID);

        assertEquals(
                SECOND_ID,
                PendingToolOperationIdLookup.find(
                                List.of(first, completed, second), "file_read", Map.of("path", "two.txt"))
                        .orElseThrow());
    }

    @Test
    void refusesMissingOrMismatchedPendingIdentity() {
        ReasoningStep legacy = ReasoningStep.pending("read", "file_read", Map.of("path", "one.txt"));

        assertTrue(PendingToolOperationIdLookup.find(List.of(legacy), "file_read", Map.of("path", "one.txt"))
                .isEmpty());
        assertTrue(PendingToolOperationIdLookup.find(
                        List.of(pending("file_read", Map.of("path", "one.txt"), FIRST_ID)),
                        "file_read",
                        Map.of("path", "other.txt"))
                .isEmpty());
    }

    @Test
    void refusesDuplicatePendingOperationIdentities() {
        ReasoningStep first = pending("file_read", Map.of("path", "one.txt"), FIRST_ID);
        ReasoningStep duplicate = pending("file_write", Map.of("path", "two.txt"), FIRST_ID);

        assertTrue(PendingToolOperationIdLookup.find(List.of(first, duplicate), "file_read", Map.of("path", "one.txt"))
                .isEmpty());
        assertTrue(PendingToolOperationIdLookup.findUniquePendingIndex(List.of(first, duplicate), FIRST_ID)
                .isEmpty());
    }

    private static ReasoningStep pending(String toolName, Map<String, Object> params, String operationId) {
        return ReasoningStep.pending("read", toolName, params).withOperationId(operationId);
    }
}
