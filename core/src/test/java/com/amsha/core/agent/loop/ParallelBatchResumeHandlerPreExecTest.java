package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.message.UserMessages;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParallelBatchResumeHandlerPreExecTest {
    private static final String SESSION = "parent-session";

    private StatefulAgentStore agentStore;
    private ToolRegistry toolRegistry;
    private ToolDelegationResolver delegationResolver;
    private ToolInvocationGuard guard;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        agentStore = mock(StatefulAgentStore.class);
        toolRegistry = mock(ToolRegistry.class);
        delegationResolver = mock(ToolDelegationResolver.class);
        guard = mock(ToolInvocationGuard.class);
        // Default: every tool is authorized for its current role, and the guard allows it.
        when(delegationResolver.resolveOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
    }

    private ParallelBatchResumeHandler handler() {
        return new ParallelBatchResumeHandler(agentStore, toolRegistry, mapper, delegationResolver, guard);
    }

    @Test
    void missingGuardBlocksPendingToolBeforeInvocation() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "unsafe")));
        ParallelBatchResumeHandler handlerWithoutGuard =
                new ParallelBatchResumeHandler(agentStore, toolRegistry, mapper, delegationResolver, null);

        AgentResult result = handlerWithoutGuard.resumeParallelBatch(
                mockContext(SESSION), new ArrayList<>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry, never()).invokeToolWithOperationId(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * A checkpoint whose pending batch is {@code calls}, with no per-tool decisions and no
     * pre-executed results. Individual tests re-stub what they need.
     */
    private AgentCheckpoint checkpointWith(List<ParallelToolCall> calls) throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getPendingToolParametersJson()).thenReturn(mapper.writeValueAsString(calls));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(false);
        when(checkpoint.hasPreExecutedResults()).thenReturn(false);
        when(checkpoint.getPendingToolRole()).thenReturn("coordinator");
        when(checkpoint.getSessionId()).thenReturn(SESSION);
        return checkpoint;
    }

    private static ParallelToolCall pendingCall(
            String callId, String toolName, Map<String, Object> params, String paramsJson) {
        String identity = String.valueOf(callId) + "|" + String.valueOf(toolName);
        String operationId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))
                .toString();
        return ParallelToolCall.pending(callId, toolName, params, paramsJson).withOperationId(operationId);
    }

    private static ParallelToolCall call(String callId, String toolName) {
        return pendingCall(callId, toolName, Map.of(), "{}");
    }

    /** Extracts the persisted batch JSON from the resumed history step. */
    private static String batchJson(ReasoningStep step) {
        return String.valueOf(step.actionInput().get("batch"));
    }

    // ── Delegated pause detection ────────────────────────────────────────────

    @Test
    void delegatedPauseWithUndecodableParams_isIgnored() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "garuda")));
        // The marker is present but the base64 payload is not decodable, so the pause is skipped.
        String paused = "Delegation is " + UserMessages.DELEGATION_PAUSED_MARKER
                + ": tool 'web_fetch' needs human approval. " + UserMessages.DELEGATION_PARAMS_BASE64_MARKER + "!!!";
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ToolResponse.success(paused));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    @Test
    void delegatedPauseWithInvalidParamsJson_isIgnored() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "garuda")));
        when(toolRegistry.invokeToolWithOperationId(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(ToolResponse.success(UserMessages.delegationPaused("saraswati", "web_fetch", "not-json")));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    private static AgentExecutionContext mockContext(String sessionId) {
        return new AgentExecutionContext(
                sessionId,
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
}
