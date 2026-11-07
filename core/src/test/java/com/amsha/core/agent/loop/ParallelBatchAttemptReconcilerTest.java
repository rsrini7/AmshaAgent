package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParallelBatchAttemptReconcilerTest {

    private static final String WORKSPACE_ID = "ws-test-batch";
    private static final String SESSION_ID = "session-batch-1";
    private static final String FINGERPRINT =
            "sha256:v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private ToolAttemptStorePort attemptStore;
    private CheckpointStorePort checkpointStore;
    private CheckpointSerializer serializer;
    private StatefulAgentStore agentStore;
    private ObjectMapper objectMapper;
    private ParallelBatchAttemptReconciler reconciler;

    @BeforeEach
    void setUp() {
        attemptStore = mock(ToolAttemptStorePort.class);
        checkpointStore = mock(CheckpointStorePort.class);
        serializer = mock(CheckpointSerializer.class);
        agentStore = mock(StatefulAgentStore.class);
        objectMapper = new ObjectMapper();
        reconciler = new ParallelBatchAttemptReconciler(attemptStore, checkpointStore, serializer, objectMapper);
    }

    private ToolAttemptDto createAttempt(String opId, String attId, ToolAttemptStatus status) {
        return createAttempt(opId, attId, status, SESSION_ID);
    }

    private ToolAttemptDto createAttempt(String opId, String attId, ToolAttemptStatus status, String sessionId) {
        String canonicalAttId =
                (attId != null && attId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
                        ? attId
                        : UUID.nameUUIDFromBytes((attId != null ? attId : "default").getBytes(StandardCharsets.UTF_8))
                                .toString();
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
                canonicalAttId,
                1,
                "tool_x",
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
                status == ToolAttemptStatus.SETTLED_SUCCESS ? "result_success" : null,
                now,
                status != ToolAttemptStatus.ADMITTED ? now : null,
                runAt,
                status.isTerminal() ? now : null);
    }

    private ParallelToolCall createCall(String callId, String toolName, String opId) {
        return ParallelToolCall.pending(callId, toolName, Map.of(), "{}").withOperationId(opId);
    }

    @Test
    void reconcileChild_missingOperationId_returnsMissing() {
        ParallelToolCall call = ParallelToolCall.pending("call_0", "search", Map.of(), "{}");
        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.MissingOperationId.class, outcome);
    }

    @Test
    void reconcileChild_storeLookupThrows_returnsBlocked() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId))
                .thenThrow(new RuntimeException("DB offline"));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Blocked.class, outcome);
    }

    @Test
    void reconcileChild_noAttempt_returnsReadyToExecute() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.empty());

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.ReadyToExecute.class, outcome);
        assertTrue(((ParallelBatchAttemptReconciler.ChildOutcome.ReadyToExecute) outcome)
                .admittedAttempt()
                .isEmpty());
    }

    @Test
    void reconcileChild_sessionMismatch_returnsBlocked() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto mismatched =
                createAttempt(opId, UUID.randomUUID().toString(), ToolAttemptStatus.ADMITTED, "other-session");
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(mismatched));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Blocked.class, outcome);
    }

    @Test
    void reconcileChild_admittedOrCancelled_returnsReadyToExecute() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto admitted = createAttempt(opId, UUID.randomUUID().toString(), ToolAttemptStatus.ADMITTED);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(admitted));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.ReadyToExecute.class, outcome);
        assertTrue(((ParallelBatchAttemptReconciler.ChildOutcome.ReadyToExecute) outcome)
                .admittedAttempt()
                .isPresent());
    }

    @Test
    void reconcileChild_running_returnsBlocked() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto running = createAttempt(opId, UUID.randomUUID().toString(), ToolAttemptStatus.RUNNING);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(running));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Blocked.class, outcome);
    }

    @Test
    void reconcileChild_settledSuccess_returnsSettledWithObservation() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto settled = createAttempt(opId, "att-1", ToolAttemptStatus.SETTLED_SUCCESS);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(settled));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Settled.class, outcome);
        var settledOutcome = (ParallelBatchAttemptReconciler.ChildOutcome.Settled) outcome;
        assertEquals("result_success", settledOutcome.completedCall().observation());
        assertTrue(settledOutcome.completedCall().success());
    }

    @Test
    void reconcileChild_settledError_returnsSettledFailure() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto errorAttempt = createAttempt(opId, "att-2", ToolAttemptStatus.SETTLED_ERROR);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(errorAttempt));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Settled.class, outcome);
        var settledOutcome = (ParallelBatchAttemptReconciler.ChildOutcome.Settled) outcome;
        assertFalse(settledOutcome.completedCall().success());
    }

    @Test
    void reconcileChild_timedOut_returnsSettledFailure() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto timedOut = createAttempt(opId, "att-3", ToolAttemptStatus.TIMED_OUT);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(timedOut));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Settled.class, outcome);
        var settledOutcome = (ParallelBatchAttemptReconciler.ChildOutcome.Settled) outcome;
        assertFalse(settledOutcome.completedCall().success());
    }

    @Test
    void reconcileChild_indeterminate_returnsSettledFailureWithIndeterminateObservation() {
        String opId = UUID.randomUUID().toString();
        ParallelToolCall call = createCall("call_0", "search", opId);
        ToolAttemptDto indet = createAttempt(opId, "att-4", ToolAttemptStatus.INDETERMINATE);
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, opId)).thenReturn(Optional.of(indet));

        var outcome = reconciler.reconcileChild(WORKSPACE_ID, SESSION_ID, call);
        assertInstanceOf(ParallelBatchAttemptReconciler.ChildOutcome.Settled.class, outcome);
        var settledOutcome = (ParallelBatchAttemptReconciler.ChildOutcome.Settled) outcome;
        assertFalse(settledOutcome.completedCall().success());
        assertTrue(settledOutcome.completedCall().observation().contains("uncertain"));
    }

    @Test
    void appendAggregateResult_appendsStepAndLinksAttempts() {
        AgentCheckpoint checkpoint = new AgentCheckpointRecord();
        checkpoint.setId(50L);
        checkpoint.setSessionId(SESSION_ID);
        checkpoint.setVersion(1);

        String op1 = UUID.randomUUID().toString();
        String op2 = UUID.randomUUID().toString();
        ParallelToolCall c1 = createCall("call_0", "t1", op1).withResult("out1", true);
        ParallelToolCall c2 = createCall("call_1", "t2", op2).withResult("out2", true);

        List<ReasoningStep> history = new ArrayList<>();
        ReasoningStep pendingBatch = ReasoningStep.parallelBatch("Thinking batch", Map.of(), 5, 10);
        history.add(pendingBatch);

        when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
        when(checkpointStore.appendResultIfAbsent(
                        eq(WORKSPACE_ID), eq(50L), eq(1), any(), eq(List.of("att-1", "att-2"))))
                .thenReturn(CheckpointAppendResult.APPENDED);

        var result = reconciler.appendAggregateResult(
                WORKSPACE_ID,
                checkpoint,
                history,
                List.of(c1, c2),
                List.of(c1, c2),
                List.of("att-1", "att-2"),
                agentStore);

        assertNull(result);
        assertEquals(1, history.size());
        assertTrue(history.get(0).observation().contains("t1→OK, t2→OK"));
        assertEquals(CheckpointStatus.IN_PROGRESS, checkpoint.getStatus());
        verify(agentStore).save(checkpoint);
    }

    @Test
    void appendAggregateResult_whenAlreadyPresent_updatesHistoryWithoutDuplicates() {
        AgentCheckpoint checkpoint = new AgentCheckpointRecord();
        checkpoint.setId(50L);
        checkpoint.setSessionId(SESSION_ID);
        checkpoint.setVersion(2);

        String op1 = UUID.randomUUID().toString();
        ParallelToolCall c1 = createCall("call_0", "t1", op1).withResult("out1", true);

        List<ReasoningStep> history = new ArrayList<>();
        ReasoningStep pendingBatch = ReasoningStep.parallelBatch("Thinking batch", Map.of(), 5, 10);
        history.add(pendingBatch);

        when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
        when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(50L), eq(2), any(), any()))
                .thenReturn(CheckpointAppendResult.ALREADY_PRESENT);

        var result = reconciler.appendAggregateResult(
                WORKSPACE_ID, checkpoint, history, List.of(c1), List.of(c1), List.of("att-1"), agentStore);

        assertNull(result);
        assertEquals(1, history.size());
        assertEquals(CheckpointStatus.IN_PROGRESS, checkpoint.getStatus());
    }

    @Test
    void appendAggregateResult_whenStale_reloadsAndRetries() {
        AgentCheckpoint checkpoint = new AgentCheckpointRecord();
        checkpoint.setId(50L);
        checkpoint.setSessionId(SESSION_ID);
        checkpoint.setVersion(1);

        String op1 = UUID.randomUUID().toString();
        ParallelToolCall c1 = createCall("call_0", "t1", op1).withResult("out1", true);

        when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
        when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(50L), eq(1), any(), any()))
                .thenReturn(CheckpointAppendResult.STALE);
        CheckpointDto reloaded = mock(CheckpointDto.class);
        when(reloaded.version()).thenReturn(2);
        when(checkpointStore.findById(50L)).thenReturn(Optional.of(reloaded));
        when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(50L), eq(2), any(), any()))
                .thenReturn(CheckpointAppendResult.APPENDED);

        var result = reconciler.appendAggregateResult(
                WORKSPACE_ID, checkpoint, new ArrayList<>(), List.of(c1), List.of(c1), List.of("att-1"), agentStore);

        assertNull(result);
        verify(checkpointStore).appendResultIfAbsent(eq(WORKSPACE_ID), eq(50L), eq(2), any(), any());
    }

    @Test
    void appendAggregateResult_whenConflict_failsBatch() {
        AgentCheckpoint checkpoint = new AgentCheckpointRecord();
        checkpoint.setId(50L);
        checkpoint.setSessionId(SESSION_ID);
        checkpoint.setVersion(1);

        String op1 = UUID.randomUUID().toString();
        ParallelToolCall c1 = createCall("call_0", "t1", op1).withResult("out1", true);

        when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
        when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(50L), eq(1), any(), any()))
                .thenReturn(CheckpointAppendResult.CONFLICT);

        var result = reconciler.appendAggregateResult(
                WORKSPACE_ID, checkpoint, new ArrayList<>(), List.of(c1), List.of(c1), List.of("att-1"), agentStore);

        assertNotNull(result);
        assertEquals(CheckpointStatus.FAILED, checkpoint.getStatus());
    }
}
