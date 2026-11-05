package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolActionClaimDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.tool.api.IdempotencyMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DurableToolAttemptGuardTest {
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String SESSION_ID = "session-1";
    private static final String TOOL_NAME = "unknown_tool";

    private Instance<ToolAttemptStorePort> stores;
    private ToolAttemptStorePort store;
    private DurableToolAttemptGuard guard;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        stores = mock(Instance.class);
        store = mock(ToolAttemptStorePort.class);
        when(stores.isUnsatisfied()).thenReturn(false);
        when(stores.isAmbiguous()).thenReturn(false);
        when(stores.get()).thenReturn(store);
        guard = new DurableToolAttemptGuard(stores, new ActionFingerprintGenerator(), new ToolTargetResolver());
    }

    @Test
    void activeLockBlocksBeforeClaimOrHistoryDeduplication() {
        when(store.findApplicableLocks(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(List.of(mock(DurableActionLockDto.class)));

        DurableToolAttemptGuard.Decision decision = guard.check(
                        WORKSPACE_ID, SESSION_ID, TOOL_NAME, Map.of("value", "same"), IdempotencyMode.AT_MOST_ONCE)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.BLOCKED, decision.kind());
        verify(store, never()).findClaim(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void atMostOnceSuccessReturnsStoredObservationWithoutNewClaim() {
        ToolAttemptDto attempt = mock(ToolAttemptDto.class);
        when(attempt.status()).thenReturn(ToolAttemptStatus.SETTLED_SUCCESS);
        when(attempt.sanitizedObservation()).thenReturn("stored result");
        when(store.findAtMostOnceResult(anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(Optional.of(attempt));

        DurableToolAttemptGuard.Decision decision = guard.check(
                        WORKSPACE_ID, SESSION_ID, TOOL_NAME, Map.of("value", "same"), IdempotencyMode.AT_MOST_ONCE)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.REPLAY, decision.kind());
        assertEquals("stored result", decision.observation());
        verify(store, never()).findClaim(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void atMostOnceFailureReplaysStoredFailureWithoutReexecution() {
        ToolAttemptDto attempt = mock(ToolAttemptDto.class);
        when(attempt.status()).thenReturn(ToolAttemptStatus.SETTLED_ERROR);
        when(attempt.sanitizedObservation()).thenReturn("stored error");
        when(store.findAtMostOnceResult(anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(Optional.of(attempt));

        DurableToolAttemptGuard.Decision decision = guard.check(
                        WORKSPACE_ID, SESSION_ID, TOOL_NAME, Map.of("value", "same"), IdempotencyMode.AT_MOST_ONCE)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.REPLAY, decision.kind());
        assertEquals("stored error", decision.observation());
        assertFalse(decision.succeeded());
        assertEquals("TOOL_ATTEMPT_ERROR", decision.errorCode());
        verify(store, never()).findClaim(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void existingAtMostOnceClaimWithoutReplayableResultBlocks() {
        when(store.findClaim(anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(Optional.of(mock(ToolActionClaimDto.class)));

        DurableToolAttemptGuard.Decision decision = guard.check(
                        WORKSPACE_ID, SESSION_ID, TOOL_NAME, Map.of("value", "same"), IdempotencyMode.AT_MOST_ONCE)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.BLOCKED, decision.kind());
    }

    @Test
    void missingWorkspaceContextBlocks() {
        DurableToolAttemptGuard.Decision decision = guard.check(
                        null, SESSION_ID, TOOL_NAME, Map.of(), IdempotencyMode.NON_IDEMPOTENT)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.BLOCKED, decision.kind());
        assertTrue(decision.observation().contains("Workspace context"));
    }

    @Test
    void persistenceFailureBlocksClosed() {
        when(store.findApplicableLocks(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("storage unavailable"));

        DurableToolAttemptGuard.Decision decision = guard.check(
                        WORKSPACE_ID, SESSION_ID, TOOL_NAME, Map.of("value", "same"), IdempotencyMode.NON_IDEMPOTENT)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.BLOCKED, decision.kind());
    }

    @Test
    void unsupportedParameterTypeBlocksClosed() {
        DurableToolAttemptGuard.Decision decision = guard.check(
                        WORKSPACE_ID,
                        SESSION_ID,
                        TOOL_NAME,
                        Map.of("unsupported", new Object()),
                        IdempotencyMode.NON_IDEMPOTENT)
                .orElseThrow();

        assertEquals(DurableToolAttemptGuard.Decision.Kind.BLOCKED, decision.kind());
    }
}
