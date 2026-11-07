package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SequentialAttemptReconcilerTest {

    private static final String WORKSPACE_ID = "ws-test-1";
    private static final String SESSION_ID = "session-1";
    private static final String TOOL_NAME = "web_search";
    private static final String PARAMS_JSON = "{\"query\":\"agentic ai\"}";
    private static final String FINGERPRINT =
            "sha256:v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private ToolAttemptStorePort attemptStore;
    private CheckpointStorePort checkpointStore;
    private CheckpointSerializer serializer;
    private ObjectMapper objectMapper;
    private SequentialAttemptReconciler reconciler;

    @BeforeEach
    void setUp() {
        attemptStore = mock(ToolAttemptStorePort.class);
        checkpointStore = mock(CheckpointStorePort.class);
        serializer = mock(CheckpointSerializer.class);
        objectMapper = new ObjectMapper();
        reconciler = new SequentialAttemptReconciler(attemptStore, checkpointStore, serializer, objectMapper);
    }

    private AgentExecutionContext createContext() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn(SESSION_ID);
        when(ctx.agentRole()).thenReturn("developer");
        return ctx;
    }

    private AgentCheckpoint createCheckpoint(Long id, int version) {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(id);
        cp.setSessionId(SESSION_ID);
        cp.setAgentRole("developer");
        cp.setVersion(version);
        cp.setPendingToolName(TOOL_NAME);
        cp.setPendingToolParametersJson(PARAMS_JSON);
        return cp;
    }

    private ToolAttemptDto createAttempt(String opId, String attId, ToolAttemptStatus status) {
        return createAttempt(opId, attId, status, SESSION_ID);
    }

    private ToolAttemptDto createAttempt(String opId, String attId, ToolAttemptStatus status, String sessionId) {
        Instant now = Instant.now();
        Instant runAt = (status == ToolAttemptStatus.RUNNING
                        || status == ToolAttemptStatus.SETTLED_SUCCESS
                        || status == ToolAttemptStatus.SETTLED_ERROR
                        || status == ToolAttemptStatus.TIMED_OUT
                        || status == ToolAttemptStatus.INDETERMINATE)
                ? now
                : null;
        return new ToolAttemptDto(
                WORKSPACE_ID,
                sessionId,
                opId,
                attId,
                1,
                TOOL_NAME,
                FINGERPRINT,
                1,
                null,
                ToolAttemptIdempotencyMode.IDEMPOTENT,
                status,
                0L,
                "BUILT_IN",
                null,
                null,
                null,
                status == ToolAttemptStatus.SETTLED_SUCCESS ? "search results" : null,
                now,
                status != ToolAttemptStatus.ADMITTED ? now : null,
                runAt,
                status.isTerminal() ? now : null);
    }

    private List<ReasoningStep> createPendingHistory(String opId) {
        return new ArrayList<>(
                List.of(ReasoningStep.pending("Searching", TOOL_NAME, Map.of("query", "agentic ai"), 10, 5)
                        .withOperationId(opId)));
    }

    @Test
    void reconcile_whenActiveWorkspaceMissing_returnsBlocked() {
        AgentExecutionContext ctx = createContext();
        AgentCheckpoint cp = createCheckpoint(1L, 0);
        List<ReasoningStep> history = new ArrayList<>();

        var outcome = reconciler.reconcile(
                ctx, cp, history, TOOL_NAME, PARAMS_JSON, UUID.randomUUID().toString());
        assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Blocked.class, outcome);
    }

    @Test
    void reconcile_whenStoreLookupThrows_failsClosed() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(1L, 0);
            String opId = UUID.randomUUID().toString();
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId))
                    .thenThrow(new RuntimeException("DB offline"));

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Blocked.class, outcome);
            assertTrue(((SequentialAttemptReconciler.ReconciliationOutcome.Blocked) outcome)
                    .reason()
                    .contains("failed"));
            return null;
        });
    }

    @Test
    void reconcile_whenNoAttempt_returnsProceedToExecution() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(1L, 0);
            String opId = UUID.randomUUID().toString();
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.empty());

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.ProceedToExecution.class, outcome);
            assertTrue(((SequentialAttemptReconciler.ReconciliationOutcome.ProceedToExecution) outcome)
                    .admittedAttempt()
                    .isEmpty());
            return null;
        });
    }

    @Test
    void reconcile_whenAttemptSessionMismatch_returnsBlocked() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(1L, 0);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto mismatched = createAttempt(opId, attId, ToolAttemptStatus.ADMITTED, "other-session");
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(mismatched));

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Blocked.class, outcome);
            assertTrue(((SequentialAttemptReconciler.ReconciliationOutcome.Blocked) outcome)
                    .reason()
                    .contains("mismatch"));
            return null;
        });
    }

    @Test
    void reconcile_whenAttemptIsAdmitted_returnsProceedToExecutionWithExistingAttempt() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(1L, 0);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto admitted = createAttempt(opId, attId, ToolAttemptStatus.ADMITTED);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(admitted));

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.ProceedToExecution.class, outcome);
            var proceed = (SequentialAttemptReconciler.ReconciliationOutcome.ProceedToExecution) outcome;
            assertTrue(proceed.admittedAttempt().isPresent());
            assertEquals(attId, proceed.admittedAttempt().get().attemptId());
            return null;
        });
    }

    @Test
    void reconcile_whenAttemptIsCancelledBeforeLaunch_returnsProceedToExecution() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(1L, 0);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto cancelled = createAttempt(opId, attId, ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(cancelled));

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.ProceedToExecution.class, outcome);
            return null;
        });
    }

    @Test
    void reconcile_whenAttemptIsRunning_returnsBlocked() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(1L, 0);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto running = createAttempt(opId, attId, ToolAttemptStatus.RUNNING);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(running));

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Blocked.class, outcome);
            assertTrue(((SequentialAttemptReconciler.ReconciliationOutcome.Blocked) outcome)
                    .reason()
                    .contains("non-terminal"));
            return null;
        });
    }

    @Test
    void reconcile_whenSettledSuccess_appendsResultAndReturnsReconciled() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 2);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto settled = createAttempt(opId, attId, ToolAttemptStatus.SETTLED_SUCCESS);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));
            List<ReasoningStep> history = createPendingHistory(opId);

            ReasoningStepDto expectedDto = new ReasoningStepDto(
                    "\"Searching\"",
                    TOOL_NAME,
                    "{\"query\":\"agentic ai\"}",
                    "search results",
                    10,
                    5,
                    true,
                    null,
                    null,
                    null);
            when(serializer.toDto(any(ReasoningStep.class))).thenReturn(expectedDto);
            when(checkpointStore.appendResultIfAbsent(
                            eq(WORKSPACE_ID), eq(10L), eq(2), eq(expectedDto), eq(List.of(attId))))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            var outcome = reconciler.reconcile(ctx, cp, history, TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Reconciled.class, outcome);
            var reconciled = (SequentialAttemptReconciler.ReconciliationOutcome.Reconciled) outcome;
            assertFalse(reconciled.indeterminate());
            assertEquals("search results", reconciled.reconciledStep().observation());
            assertEquals(Boolean.TRUE, reconciled.reconciledStep().success());
            assertNull(reconciled.reconciledStep().errorCode());

            // History was updated in-place without duplicate
            assertEquals(1, history.size());
            assertEquals("search results", history.get(0).observation());
            assertEquals(opId, history.get(0).operationId());
            return null;
        });
    }

    @Test
    void reconcile_whenSettledError_mapsErrorCodeCorrectly() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 1);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto settled = createAttempt(opId, attId, ToolAttemptStatus.SETTLED_ERROR);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));
            List<ReasoningStep> history = createPendingHistory(opId);

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(any(), any(), anyInt(), any(), any()))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            var outcome = reconciler.reconcile(ctx, cp, history, TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Reconciled.class, outcome);
            var reconciled = (SequentialAttemptReconciler.ReconciliationOutcome.Reconciled) outcome;
            assertFalse(reconciled.indeterminate());
            assertEquals(Boolean.FALSE, reconciled.reconciledStep().success());
            assertEquals("TOOL_ATTEMPT_ERROR", reconciled.reconciledStep().errorCode());
            return null;
        });
    }

    @Test
    void reconcile_whenTimedOut_mapsTimeoutErrorCode() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 1);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto timedOut = createAttempt(opId, attId, ToolAttemptStatus.TIMED_OUT);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(timedOut));
            List<ReasoningStep> history = createPendingHistory(opId);

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(any(), any(), anyInt(), any(), any()))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            var outcome = reconciler.reconcile(ctx, cp, history, TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Reconciled.class, outcome);
            var reconciled = (SequentialAttemptReconciler.ReconciliationOutcome.Reconciled) outcome;
            assertFalse(reconciled.indeterminate());
            assertEquals(Boolean.FALSE, reconciled.reconciledStep().success());
            assertEquals("TOOL_ATTEMPT_TIMED_OUT", reconciled.reconciledStep().errorCode());
            return null;
        });
    }

    @Test
    void reconcile_whenIndeterminate_recordsIndeterminateAndReturnsFlag() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 1);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto indet = createAttempt(opId, attId, ToolAttemptStatus.INDETERMINATE);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(indet));
            List<ReasoningStep> history = createPendingHistory(opId);

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(any(), any(), anyInt(), any(), any()))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            var outcome = reconciler.reconcile(ctx, cp, history, TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Reconciled.class, outcome);
            var reconciled = (SequentialAttemptReconciler.ReconciliationOutcome.Reconciled) outcome;
            assertTrue(reconciled.indeterminate());
            assertEquals(Boolean.FALSE, reconciled.reconciledStep().success());
            assertEquals("INDETERMINATE_OUTCOME", reconciled.reconciledStep().errorCode());
            return null;
        });
    }

    @Test
    void reconcile_whenAlreadyPresent_updatesHistoryWithoutDuplicates() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 3);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto settled = createAttempt(opId, attId, ToolAttemptStatus.SETTLED_SUCCESS);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));
            List<ReasoningStep> history = createPendingHistory(opId);

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(any(), any(), anyInt(), any(), any()))
                    .thenReturn(CheckpointAppendResult.ALREADY_PRESENT);

            var outcome = reconciler.reconcile(ctx, cp, history, TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Reconciled.class, outcome);

            // Verified history updated in place, no duplicate added
            assertEquals(1, history.size());
            assertEquals("search results", history.get(0).observation());
            return null;
        });
    }

    @Test
    void reconcile_whenStale_reloadsVersionAndRetries() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 1);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto settled = createAttempt(opId, attId, ToolAttemptStatus.SETTLED_SUCCESS);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));
            List<ReasoningStep> history = createPendingHistory(opId);

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            // First call fails with STALE at version 1
            when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(10L), eq(1), any(), any()))
                    .thenReturn(CheckpointAppendResult.STALE);
            // Reload returns version 2
            CheckpointDto reloaded = mock(CheckpointDto.class);
            when(reloaded.version()).thenReturn(2);
            when(checkpointStore.findById(10L)).thenReturn(Optional.of(reloaded));
            // Second call succeeds with APPENDED at version 2
            when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(10L), eq(2), any(), any()))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            var outcome = reconciler.reconcile(ctx, cp, history, TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Reconciled.class, outcome);
            verify(checkpointStore).appendResultIfAbsent(eq(WORKSPACE_ID), eq(10L), eq(2), any(), any());
            return null;
        });
    }

    @Test
    void reconcile_whenConflict_returnsBlocked() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            AgentExecutionContext ctx = createContext();
            AgentCheckpoint cp = createCheckpoint(10L, 1);
            String opId = UUID.randomUUID().toString();
            String attId = UUID.randomUUID().toString();
            ToolAttemptDto settled = createAttempt(opId, attId, ToolAttemptStatus.SETTLED_SUCCESS);
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(any(), any(), anyInt(), any(), any()))
                    .thenReturn(CheckpointAppendResult.CONFLICT);

            var outcome = reconciler.reconcile(ctx, cp, new ArrayList<>(), TOOL_NAME, PARAMS_JSON, opId);
            assertInstanceOf(SequentialAttemptReconciler.ReconciliationOutcome.Blocked.class, outcome);
            return null;
        });
    }

    @Test
    void cancelAbandonedAttemptIfAdmitted_cancelsAdmittedAttempt() {
        AgentCheckpoint cp = createCheckpoint(10L, 1);
        String opId = UUID.randomUUID().toString();
        String attId = UUID.randomUUID().toString();
        when(serializer.deserializeSteps(cp)).thenReturn(createPendingHistory(opId));

        ToolAttemptDto admitted = createAttempt(opId, attId, ToolAttemptStatus.ADMITTED);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(admitted));
        when(attemptStore.transition(
                        eq(WORKSPACE_ID),
                        eq(attId),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(0L),
                        eq(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList()))
                .thenReturn(ToolAttemptTransitionResult.applied(admitted));

        boolean cancelled = reconciler.cancelAbandonedAttemptIfAdmitted(WORKSPACE_ID, cp);
        assertTrue(cancelled);
        verify(attemptStore)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(attId),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(0L),
                        eq(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH),
                        any(),
                        isNull(),
                        anyList());
    }

    @Test
    void cancelAbandonedAttemptIfAdmitted_skipsWhenNotAdmitted() {
        AgentCheckpoint cp = createCheckpoint(10L, 1);
        String opId = UUID.randomUUID().toString();
        String attId = UUID.randomUUID().toString();
        when(serializer.deserializeSteps(cp)).thenReturn(createPendingHistory(opId));

        ToolAttemptDto settled = createAttempt(opId, attId, ToolAttemptStatus.SETTLED_SUCCESS);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));

        boolean cancelled = reconciler.cancelAbandonedAttemptIfAdmitted(WORKSPACE_ID, cp);
        assertFalse(cancelled);
        verify(attemptStore, never()).transition(any(), any(), any(), anyLong(), any(), any(), any(), any());
    }
}
