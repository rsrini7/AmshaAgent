package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.message.UserMessages;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ParallelBatchResumeHandlerTest {

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
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
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

    private static ParallelToolCall call(String callId, String toolName) {
        return ParallelToolCall.pending(callId, toolName, Map.of(), "{}");
    }

    /** Extracts the persisted batch JSON from the resumed history step. */
    private static String batchJson(ReasoningStep step) {
        return String.valueOf(step.actionInput().get("batch"));
    }

    // ── Existing regression coverage ─────────────────────────────────────────

    @Test
    void delegatedApprovalPauseDoesNotCompleteParentBatch() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        ParallelToolCall call = ParallelToolCall.pending(
                "call-1",
                "garuda",
                Map.of("agent", "saraswati", "goal", "research"),
                mapper.writeValueAsString(Map.of("agent", "saraswati", "goal", "research")));
        when(checkpoint.getPendingToolParametersJson()).thenReturn(mapper.writeValueAsString(List.of(call)));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("{\"garuda\":\"APPROVED\"}");
        when(checkpoint.hasPreExecutedResults()).thenReturn(false);
        when(checkpoint.getPendingToolRole()).thenReturn("coordinator");
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.RESUMING);
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString()))
                .thenReturn(ToolResponse.success(UserMessages.delegationPaused(
                        "saraswati",
                        "web_fetch",
                        "[{\"toolName\":\"web_fetch\",\"params\":{\"url\":\"https://example.com\"}}]")));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        AgentResult.AwaitingApproval awaiting = assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        assertEquals("web_fetch", awaiting.toolName());
        verify(checkpoint).setStatus(CheckpointStatus.AWAITING_APPROVAL);
        verify(agentStore).save(checkpoint);
    }

    @Test
    void consumesObjectShapedPreExecutedResultWithoutReinvokingTool() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        ParallelToolCall call = ParallelToolCall.pending("call-1", "safe", Map.of(), "{}");

        when(checkpoint.getPendingToolParametersJson()).thenReturn(mapper.writeValueAsString(List.of(call)));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("{\"safe\":\"APPROVED\"}");
        when(checkpoint.hasPreExecutedResults()).thenReturn(true);
        when(checkpoint.getPreExecutedResults())
                .thenReturn(mapper.writerFor(new TypeReference<Map<String, ToolResponse>>() {})
                        .writeValueAsString(Map.of("call-1", ToolResponse.success("already done"))));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    // ── Corrupt payloads ─────────────────────────────────────────────────────

    @Test
    void unparseableBatchPayload_marksTheCheckpointFailed() {
        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getPendingToolParametersJson()).thenReturn("[not-json");
        when(checkpoint.getSessionId()).thenReturn(SESSION);

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, result);
        assertEquals("Failed to finalize parallel tool batch.", failed.reason());
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
        verify(checkpoint).setStatus(CheckpointStatus.FAILED);
        verify(agentStore).save(checkpoint);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
    }

    @Test
    void unparseableBatchDecisions_fallBackToPendingAndInvoke() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("not-a-decision-map");
        when(toolRegistry.invokeTool(eq("safe"), eq("{}"), eq(SESSION))).thenReturn(ToolResponse.success("ok"));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        // A failed decision parse must degrade to PENDING, not to DENIED.
        verify(toolRegistry).invokeTool(eq("safe"), eq("{}"), eq(SESSION));
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    @Test
    void unparseablePreExecutedResults_fallThroughToInvocation() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(checkpoint.hasPreExecutedResults()).thenReturn(true);
        when(checkpoint.getPreExecutedResults()).thenReturn("not-json");
        when(toolRegistry.invokeTool(eq("safe"), eq("{}"), eq(SESSION))).thenReturn(ToolResponse.success("ok"));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry).invokeTool(eq("safe"), eq("{}"), eq(SESSION));
    }

    @Test
    void unparseableSinglePreExecutedEntry_fallsBackToInvocation() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(checkpoint.hasPreExecutedResults()).thenReturn(true);
        when(checkpoint.getPreExecutedResults()).thenReturn("{\"call-1\":\"not-json\"}");
        when(toolRegistry.invokeTool(eq("safe"), eq("{}"), eq(SESSION))).thenReturn(ToolResponse.success("ok"));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry).invokeTool(eq("safe"), eq("{}"), eq(SESSION));
    }

    @Test
    void finalizationFailure_marksTheCheckpointFailed() throws Exception {
        ObjectMapper throwing = mock(ObjectMapper.class);
        // Delegate to the real mapper, but fail when the handler serializes the completed batch.
        // The String local is required: readValue(String, TypeReference) is otherwise ambiguous
        // against the InputStream/byte[] overloads during inference.
        when(throwing.readValue(anyString(), any(TypeReference.class))).thenAnswer(inv -> {
            String json = inv.getArgument(0);
            return mapper.readValue(json, (TypeReference<?>) inv.getArgument(1));
        });
        when(throwing.writeValueAsString(any())).thenAnswer(inv -> {
            Object argument = inv.getArgument(0);
            if (argument instanceof List) throw new IllegalStateException("cannot serialize batch");
            return mapper.writeValueAsString(argument);
        });

        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getPendingToolParametersJson())
                .thenReturn(mapper.writeValueAsString(List.of(call("c1", "safe"))));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(false);
        when(checkpoint.hasPreExecutedResults()).thenReturn(false);
        when(checkpoint.getPendingToolRole()).thenReturn("coordinator");
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString())).thenReturn(ToolResponse.success("ok"));

        ParallelBatchResumeHandler failing =
                new ParallelBatchResumeHandler(agentStore, toolRegistry, throwing, delegationResolver, guard);
        AgentResult result = failing.resumeParallelBatch(
                mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, result);
        assertEquals("Failed to finalize parallel tool batch.", failed.reason());
        verify(checkpoint).setStatus(CheckpointStatus.FAILED);
        verify(agentStore).save(checkpoint);
    }

    // ── Per-tool decisions ───────────────────────────────────────────────────

    @Test
    void preExecutedFailureAndPendingShapes_areMappedOntoTheirCalls() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "a"), call("call-2", "b")));
        when(checkpoint.hasPreExecutedResults()).thenReturn(true);
        when(checkpoint.getPreExecutedResults())
                .thenReturn(mapper.writerFor(new TypeReference<Map<String, ToolResponse>>() {})
                        .writeValueAsString(Map.of(
                                "call-1", ToolResponse.failure(1, "boom"),
                                "call-2", ToolResponse.pending("waiting", "tok"))));

        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = handler().resumeParallelBatch(mockContext(SESSION), history, checkpoint, "TELEGRAM");

        assertNull(result);
        // Both stored responses are non-success, so neither call is re-invoked.
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
        assertEquals("a→FAIL, b→FAIL", history.getFirst().observation());
    }

    @Test
    void decisionLookupFallsBackToCallIdWhenTheToolNameKeyIsAbsent() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("{\"call-1\":\"APPROVED\"}");
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString())).thenReturn(ToolResponse.success("ok"));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry).invokeTool(eq("safe"), eq("{}"), eq(SESSION));
    }

    @Test
    void deniedDecision_marksTheCallDeniedWithoutInvokingTheTool() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(true);
        when(checkpoint.getBatchToolDecisions()).thenReturn("{\"safe\":\"DENIED\"}");

        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = handler().resumeParallelBatch(mockContext(SESSION), history, checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
        assertTrue(
                batchJson(history.getFirst()).contains("User denied this tool execution."),
                batchJson(history.getFirst()));
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    // ── Delegation redirect ──────────────────────────────────────────────────

    @Test
    void unauthorizedBatchTool_isRedirectedToItsOwnerViaDelegation() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(
                List.of(ParallelToolCall.pending("call-1", "github", Map.of("q", "x"), "{\"q\":\"x\"}")));
        when(delegationResolver.resolveOwner("coordinator", "github")).thenReturn(Optional.of("researcher"));
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString())).thenReturn(ToolResponse.success("ok"));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        ArgumentCaptor<String> paramsJson = ArgumentCaptor.forClass(String.class);
        verify(toolRegistry)
                .invokeTool(eq(ToolCapability.DELEGATE_TO_AGENT.toolName()), paramsJson.capture(), eq(SESSION));
        assertTrue(paramsJson.getValue().contains("\"agent\":\"researcher\""), paramsJson.getValue());
        assertTrue(paramsJson.getValue().contains("github"), paramsJson.getValue());
    }

    @Test
    void delegationRedirectWithUnparseableParams_stillBuildsTheDelegation() throws Exception {
        AgentCheckpoint checkpoint =
                checkpointWith(List.of(ParallelToolCall.pending("call-1", "github", Map.of(), "{not-json")));
        when(delegationResolver.resolveOwner("coordinator", "github")).thenReturn(Optional.of("researcher"));
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString())).thenReturn(ToolResponse.success("ok"));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        // A params-parse failure is non-fatal: the delegation is still issued, with an empty arg map.
        verify(toolRegistry).invokeTool(eq(ToolCapability.DELEGATE_TO_AGENT.toolName()), anyString(), eq(SESSION));
    }

    @Test
    void delegationParamsSerializationFailure_isRecordedAsAToolFailure() throws Exception {
        ObjectMapper throwing = mock(ObjectMapper.class);
        // Delegate to the real mapper, but fail when the handler serializes a Map (the delegation
        // params). The String local is required: readValue(String, TypeReference) is otherwise
        // ambiguous against the InputStream/byte[] overloads during inference.
        when(throwing.readValue(anyString(), any(TypeReference.class))).thenAnswer(inv -> {
            String json = inv.getArgument(0);
            return mapper.readValue(json, (TypeReference<?>) inv.getArgument(1));
        });
        when(throwing.writeValueAsString(any())).thenAnswer(inv -> {
            Object argument = inv.getArgument(0);
            if (argument instanceof Map) throw new IllegalStateException("cannot serialize delegation params");
            return mapper.writeValueAsString(argument);
        });

        AgentCheckpoint checkpoint = mock(AgentCheckpoint.class);
        when(checkpoint.getPendingToolParametersJson())
                .thenReturn(mapper.writeValueAsString(
                        List.of(ParallelToolCall.pending("call-1", "github", Map.of("q", "x"), "{\"q\":\"x\"}"))));
        when(checkpoint.hasBatchToolDecisions()).thenReturn(false);
        when(checkpoint.hasPreExecutedResults()).thenReturn(false);
        when(checkpoint.getPendingToolRole()).thenReturn("coordinator");
        when(delegationResolver.resolveOwner("coordinator", "github")).thenReturn(Optional.of("researcher"));

        ParallelBatchResumeHandler failing =
                new ParallelBatchResumeHandler(agentStore, toolRegistry, throwing, delegationResolver, guard);
        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = failing.resumeParallelBatch(mockContext(SESSION), history, checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
        assertTrue(
                batchJson(history.getFirst()).contains("Tool execution failed on resume"),
                batchJson(history.getFirst()));
    }

    // ── Guard decisions ──────────────────────────────────────────────────────

    @Test
    void blockedGuardDecision_marksTheCallBlocked() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Blocked("policy violation"));

        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = handler().resumeParallelBatch(mockContext(SESSION), history, checkpoint, "TELEGRAM");

        assertNull(result);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
        assertTrue(
                batchJson(history.getFirst()).contains("Tool execution blocked by safety guard: policy violation"),
                batchJson(history.getFirst()));
    }

    @Test
    void pauseForApproval_marksTheCheckpointAwaitingAndReturnsTheFrame() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        ApprovalFrame frame = new ApprovalFrame("safe", Map.of(), RiskLevel.MEDIUM);
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(frame));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        AgentResult.AwaitingApproval awaiting = assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        assertSame(frame, awaiting.frame());
        verify(checkpoint).setStatus(CheckpointStatus.AWAITING_APPROVAL);
        verify(agentStore).save(checkpoint);
        verify(toolRegistry, never()).invokeTool(anyString(), anyString(), anyString());
    }

    // ── Invocation outcomes ──────────────────────────────────────────────────

    @Test
    void failureAndPendingResponses_areMappedOntoTheirCalls() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "a"), call("call-2", "b")));
        when(toolRegistry.invokeTool(eq("a"), anyString(), anyString())).thenReturn(ToolResponse.failure(1, "boom"));
        when(toolRegistry.invokeTool(eq("b"), anyString(), anyString()))
                .thenReturn(ToolResponse.pending("waiting", "tok"));

        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = handler().resumeParallelBatch(mockContext(SESSION), history, checkpoint, "TELEGRAM");

        assertNull(result);
        assertEquals("a→FAIL, b→FAIL", history.getFirst().observation());
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    @Test
    void invocationException_isRecordedAsAToolFailure() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "safe")));
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("kaboom"));

        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = handler().resumeParallelBatch(mockContext(SESSION), history, checkpoint, "TELEGRAM");

        assertNull(result);
        assertEquals("safe→FAIL", history.getFirst().observation());
        assertTrue(
                batchJson(history.getFirst()).contains("Tool execution failed on resume: kaboom"),
                batchJson(history.getFirst()));
    }

    // ── Delegated pause detection ────────────────────────────────────────────

    @Test
    void delegatedPauseWithUndecodableParams_isIgnored() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "garuda")));
        // The marker is present but the base64 payload is not decodable, so the pause is skipped.
        String paused = "Delegation is " + UserMessages.DELEGATION_PAUSED_MARKER
                + ": tool 'web_fetch' needs human approval. " + UserMessages.DELEGATION_PARAMS_BASE64_MARKER + "!!!";
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString())).thenReturn(ToolResponse.success(paused));

        AgentResult result = handler()
                .resumeParallelBatch(mockContext(SESSION), new ArrayList<ReasoningStep>(), checkpoint, "TELEGRAM");

        assertNull(result);
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    @Test
    void delegatedPauseWithInvalidParamsJson_isIgnored() throws Exception {
        AgentCheckpoint checkpoint = checkpointWith(List.of(call("call-1", "garuda")));
        when(toolRegistry.invokeTool(anyString(), anyString(), anyString()))
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
