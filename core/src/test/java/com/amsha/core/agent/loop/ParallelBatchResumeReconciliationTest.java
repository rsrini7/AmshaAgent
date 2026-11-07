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
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.state.CheckpointSerializer;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointAppendResult;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.CheckpointStorePort;
import com.amsha.persistence.api.ReasoningStepDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParallelBatchResumeReconciliationTest {

    private static final String WORKSPACE_ID = "ws-batch-reconcile";
    private static final String SESSION_ID = "session-batch-2";
    private static final String FINGERPRINT =
            "sha256:v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private StatefulAgentStore agentStore;
    private ToolRegistry toolRegistry;
    private ToolDelegationResolver delegationResolver;
    private ToolInvocationGuard guard;
    private ToolAttemptStorePort attemptStore;
    private CheckpointStorePort checkpointStore;
    private CheckpointSerializer serializer;
    private final ObjectMapper mapper = new ObjectMapper();

    private ParallelBatchAttemptReconciler batchReconciler;
    private ParallelBatchResumeHandler resumeHandler;

    @BeforeEach
    void setUp() {
        agentStore = mock(StatefulAgentStore.class);
        toolRegistry = mock(ToolRegistry.class);
        delegationResolver = mock(ToolDelegationResolver.class);
        guard = mock(ToolInvocationGuard.class);
        attemptStore = mock(ToolAttemptStorePort.class);
        checkpointStore = mock(CheckpointStorePort.class);
        serializer = mock(CheckpointSerializer.class);

        when(delegationResolver.resolveOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());

        batchReconciler = new ParallelBatchAttemptReconciler(attemptStore, checkpointStore, serializer, mapper);
        resumeHandler = new ParallelBatchResumeHandler(
                agentStore, toolRegistry, mapper, delegationResolver, guard, batchReconciler);
    }

    private ToolAttemptDto createAttempt(String opId, String attId, ToolAttemptStatus status, String observation) {
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
                SESSION_ID,
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
                observation,
                now,
                status != ToolAttemptStatus.ADMITTED ? now : null,
                runAt,
                status.isTerminal() ? now : null);
    }

    private AgentCheckpoint createBatchCheckpoint(Long cpId, int version, List<ParallelToolCall> calls)
            throws Exception {
        AgentCheckpointRecord cp = new AgentCheckpointRecord();
        cp.setId(cpId);
        cp.setSessionId(SESSION_ID);
        cp.setVersion(version);
        cp.setStatus(CheckpointStatus.RESUMING);
        cp.setPendingToolName(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX + "batch");
        cp.setPendingToolRole("coordinator");
        cp.setPendingToolParametersJson(mapper.writeValueAsString(calls));
        return cp;
    }

    private AgentExecutionContext createContext() {
        return new AgentExecutionContext(
                SESSION_ID,
                "user-1",
                "chat-1",
                "coordinator",
                "goal",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                "TELEGRAM",
                "chat-1",
                "",
                Map.of(),
                List.of());
    }

    @Test
    void resumeParallelBatch_skipsSettledCallsAndRunsUnsettledCalls() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            String op1 = UUID.randomUUID().toString();
            String op2 = UUID.randomUUID().toString();
            ParallelToolCall call1 = ParallelToolCall.pending("call_0", "cached_fetch", Map.of(), "{}")
                    .withOperationId(op1);
            ParallelToolCall call2 = ParallelToolCall.pending("call_1", "live_query", Map.of(), "{}")
                    .withOperationId(op2);

            AgentCheckpoint cp = createBatchCheckpoint(100L, 1, List.of(call1, call2));

            // Child 1 is settled success
            ToolAttemptDto settled1 = createAttempt(op1, "att-1", ToolAttemptStatus.SETTLED_SUCCESS, "cached output");
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, op1)).thenReturn(Optional.of(settled1));

            // Child 2 is not yet settled
            ToolAttemptDto attempt2 = createAttempt(op2, "att-2", ToolAttemptStatus.SETTLED_SUCCESS, "live output");
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, op2))
                    .thenReturn(Optional.empty()) // before run
                    .thenReturn(Optional.of(attempt2)); // after run

            when(toolRegistry.invokeToolWithOperationId(eq("live_query"), anyString(), eq(SESSION_ID), eq(op2)))
                    .thenReturn(ToolResponse.success("live output"));

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(100L), eq(1), any(), anyList()))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            List<ReasoningStep> history = new ArrayList<>();
            AgentResult result = resumeHandler.resumeParallelBatch(createContext(), history, cp, "TELEGRAM");

            assertNull(result);
            // Verify call1 was NEVER executed by toolRegistry
            verify(toolRegistry, never())
                    .invokeToolWithOperationId(eq("cached_fetch"), anyString(), anyString(), anyString());
            // Verify call2 WAS executed by toolRegistry
            verify(toolRegistry).invokeToolWithOperationId(eq("live_query"), anyString(), eq(SESSION_ID), eq(op2));

            // History should have the aggregate step with summary in original call order
            assertEquals(1, history.size());
            assertEquals("cached_fetch→OK, live_query→OK", history.get(0).observation());
            assertEquals(CheckpointStatus.IN_PROGRESS, cp.getStatus());
            return null;
        });
    }

    @Test
    void resumeParallelBatch_withIndeterminateChild_recordsIndeterminateAndAggregates() throws Exception {
        WorkspaceContext.callWithActiveWorkspace(WORKSPACE_ID, () -> {
            String op1 = UUID.randomUUID().toString();
            String op2 = UUID.randomUUID().toString();
            ParallelToolCall call1 = ParallelToolCall.pending("call_0", "risky_tool", Map.of(), "{}")
                    .withOperationId(op1);
            ParallelToolCall call2 = ParallelToolCall.pending("call_1", "read_tool", Map.of(), "{}")
                    .withOperationId(op2);

            AgentCheckpoint cp = createBatchCheckpoint(101L, 1, List.of(call1, call2));

            // Child 1 is indeterminate
            ToolAttemptDto indet = createAttempt(op1, "att-indet", ToolAttemptStatus.INDETERMINATE, "action uncertain");
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, op1)).thenReturn(Optional.of(indet));

            // Child 2 is settled success
            ToolAttemptDto settled2 = createAttempt(op2, "att-2", ToolAttemptStatus.SETTLED_SUCCESS, "read ok");
            when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, op2)).thenReturn(Optional.of(settled2));

            when(serializer.toDto(any())).thenReturn(mock(ReasoningStepDto.class));
            when(checkpointStore.appendResultIfAbsent(eq(WORKSPACE_ID), eq(101L), eq(1), any(), any()))
                    .thenReturn(CheckpointAppendResult.APPENDED);

            List<ReasoningStep> history = new ArrayList<>();
            AgentResult result = resumeHandler.resumeParallelBatch(createContext(), history, cp, "TELEGRAM");

            assertNull(result);
            verify(toolRegistry, never()).invokeToolWithOperationId(anyString(), anyString(), anyString(), anyString());
            assertEquals(1, history.size());
            // Child 1 is FAIL, Child 2 is OK; original order preserved
            assertEquals("risky_tool→FAIL, read_tool→OK", history.get(0).observation());
            return null;
        });
    }

    @Test
    void cancelAbandonedAttemptIfAdmitted_cancelsAdmittedBatchChildren() throws Exception {
        String op1 = UUID.randomUUID().toString();
        String op2 = UUID.randomUUID().toString();
        ParallelToolCall call1 =
                ParallelToolCall.pending("call_0", "t1", Map.of(), "{}").withOperationId(op1);
        ParallelToolCall call2 =
                ParallelToolCall.pending("call_1", "t2", Map.of(), "{}").withOperationId(op2);

        AgentCheckpoint cp = createBatchCheckpoint(102L, 1, List.of(call1, call2));
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);

        ToolAttemptDto admitted1 = createAttempt(op1, "att-adm-1", ToolAttemptStatus.ADMITTED, null);
        ToolAttemptDto settled2 = createAttempt(op2, "att-set-2", ToolAttemptStatus.SETTLED_SUCCESS, "done");

        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, op1)).thenReturn(Optional.of(admitted1));
        when(attemptStore.findLatestAttemptByOperation(WORKSPACE_ID, op2)).thenReturn(Optional.of(settled2));

        when(attemptStore.transition(
                        eq(WORKSPACE_ID),
                        eq(admitted1.attemptId()),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(0L),
                        eq(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH),
                        any(ToolAttemptSettlementFields.class),
                        isNull(),
                        anyList()))
                .thenReturn(ToolAttemptTransitionResult.applied(admitted1));

        SequentialAttemptReconciler seqReconciler =
                new SequentialAttemptReconciler(attemptStore, checkpointStore, serializer, mapper);
        boolean cancelled = seqReconciler.cancelAbandonedAttemptIfAdmitted(WORKSPACE_ID, cp);

        assertTrue(cancelled);
        verify(attemptStore)
                .transition(
                        eq(WORKSPACE_ID),
                        eq(admitted1.attemptId()),
                        eq(ToolAttemptStatus.ADMITTED),
                        eq(0L),
                        eq(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH),
                        any(),
                        isNull(),
                        anyList());
        // Verify child 2 (already settled) was NEVER transitioned
        verify(attemptStore, never())
                .transition(eq(WORKSPACE_ID), eq(settled2.attemptId()), any(), anyLong(), any(), any(), any(), any());
    }
}
