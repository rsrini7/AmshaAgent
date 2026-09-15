package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class ApprovalCheckpointManagerTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentSseService agentSseService;

    ObjectMapper objectMapper = new ObjectMapper();

    ApprovalCheckpointManager manager;

    ArgumentCaptor<AgentCheckpoint> cpCaptor;

    @Mock
    TracePublisher tracePublisher;

    @Mock
    DomainEventBus domainEventBus;

    @Mock
    Instance<DomainEventBus> eventBusInstance;

    @BeforeEach
    void setUp() {
        manager = new ApprovalCheckpointManager(agentStore, agentSseService, objectMapper);
        cpCaptor = ArgumentCaptor.forClass(AgentCheckpoint.class);
    }

    private AgentExecutionContext ctx() {
        return new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "do task",
                AutonomyLevel.SUPERVISED,
                null,
                5,
                0,
                "telegram",
                "12345",
                null,
                null);
    }

    private ApprovalFrame frame() {
        return new ApprovalFrame("search_tool", Map.of("q", "hello"), RiskLevel.MEDIUM);
    }

    // ── save ────────────────────────────────────────────────────

    @Test
    void save_DelegatesToAgentStore() {
        var cp = new AgentCheckpointRecord();
        cp.setSessionId("sess-1");
        manager.save(cp);
        verify(agentStore).save(cp);
    }

    // ── persist(frame overload) ─────────────────────────────────

    @Test
    void persist_WithFrame_CreatesAwaitingApprovalCheckpoint() {
        var ctx = ctx();
        var frame = frame();
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persist(ctx, frame, history, "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
        assertEquals("search_tool", ((AgentResult.AwaitingApproval) result).toolName());

        verify(agentStore).clearBatchResuming("sess-1");
        verify(agentStore).saveWithSteps(cpCaptor.capture(), eq(history));

        AgentCheckpoint saved = cpCaptor.getValue();
        assertEquals(CheckpointStatus.AWAITING_APPROVAL, saved.getStatus());
        assertEquals("search_tool", saved.getPendingToolName());
        assertEquals("coordinator", saved.getPendingToolRole());
        assertEquals("coordinator", saved.getAgentRole());
        assertEquals(Integer.valueOf(5), saved.getMaxIterations());
        assertEquals("do task", saved.getOriginalGoal());

        verify(agentSseService).publish(eq("sess-1"), any(AgentSseService.HitlEvent.class));
    }

    @Test
    void persist_WithFrame_StampsChannelInfoFromContext() {
        var ctx = ctx();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        manager.persist(ctx, frame(), List.of(), "telegram", "12345");

        verify(agentStore).saveWithSteps(cpCaptor.capture(), anyList());
        assertEquals("telegram", cpCaptor.getValue().getChannelType());
        assertEquals("12345", cpCaptor.getValue().getChannelPlatformId());
    }

    @Test
    void persist_WithFrame_ResumesExistingActiveCheckpoint() {
        var ctx = ctx();
        var existing = new AgentCheckpointRecord();
        existing.setSessionId("sess-1");
        existing.setAgentRole("coordinator");
        existing.setStatus(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.of(existing));
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        manager.persist(ctx, frame(), List.of(), "telegram", "12345");

        verify(agentStore).saveWithSteps(cpCaptor.capture(), anyList());
        assertSame(existing, cpCaptor.getValue());
    }

    @Test
    void persist_WithFrame_SetsBatchToolDecisionsForSingleTool() throws Exception {
        var ctx = ctx();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        manager.persist(ctx, frame(), List.of(), "telegram", "12345");

        verify(agentStore).saveWithSteps(cpCaptor.capture(), anyList());
        String decisions = cpCaptor.getValue().getBatchToolDecisions();
        assertNotNull(decisions);
        @SuppressWarnings("unchecked")
        Map<String, String> map = objectMapper.readValue(decisions, Map.class);
        assertEquals(ToolDecision.PENDING.name(), map.get("search_tool"));
    }

    @Test
    void persistDelegatedWait_KeepsParentInProgressWithApprovedDelegation() throws Exception {
        var context = ctx();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        String delegationTool = ToolCapability.DELEGATE_TO_AGENT.toolName();
        assertTrue(manager.persistDelegatedWait(context, delegationTool, Map.of("agent", "saraswati"), List.of()));

        verify(agentStore).saveWithSteps(cpCaptor.capture(), anyList());
        AgentCheckpoint saved = cpCaptor.getValue();
        assertEquals(CheckpointStatus.IN_PROGRESS, saved.getStatus());
        assertEquals(delegationTool, saved.getPendingToolName());
        assertEquals("coordinator", saved.getPendingToolRole());
        assertEquals("{\"agent\":\"saraswati\"}", saved.getPendingToolParametersJson());
        @SuppressWarnings("unchecked")
        Map<String, String> decisions = objectMapper.readValue(saved.getBatchToolDecisions(), Map.class);
        assertEquals(ToolDecision.APPROVED.name(), decisions.get(delegationTool));
    }

    // ── persist(string overload) ────────────────────────────────

    @Test
    void persist_WithStringOverload_ParsesParamsAndDelegates() {
        var ctx = ctx();
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persist(ctx, "search_tool", "{\"q\":\"test\"}", history, "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
        assertEquals("search_tool", ((AgentResult.AwaitingApproval) result).toolName());
    }

    @Test
    void persist_WithStringOverload_HandlesBlankChannelInfo() {
        var ctx = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "do task",
                AutonomyLevel.SUPERVISED,
                null,
                5,
                0,
                null,
                null,
                null,
                null);
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persist(ctx, "search_tool", "{}", List.of(), null, null);

        assertTrue(result instanceof AgentResult.AwaitingApproval);
    }

    // ── persistBatch ────────────────────────────────────────────

    @Test
    void persistBatch_CreatesCompositeNameAndPersists() {
        var ctx = ctx();
        var calls = List.of(
                ParallelToolCall.pending("call_0", "web_search", Map.of(), "{}"),
                ParallelToolCall.pending("call_1", "read_file", Map.of(), "{}"));
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persistBatch(ctx, calls, history, "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
        assertTrue(((AgentResult.AwaitingApproval) result).toolName().contains("web_search|read_file"));

        verify(agentStore).saveWithSteps(cpCaptor.capture(), eq(history));
        assertTrue(cpCaptor.getValue().getPendingToolName().contains("web_search|read_file"));
    }

    // ── persistBatchWithPreExecuted ─────────────────────────────

    @Test
    void persistBatchWithPreExecuted_MarksExecutedToolsAsApproved() throws Exception {
        var ctx = ctx();
        var allCalls = List.of(
                ParallelToolCall.pending("call_0", "search", Map.of(), "{}"),
                ParallelToolCall.pending("call_1", "read", Map.of(), "{}"));
        Map<String, ToolResponse> results = Map.of("search", ToolResponse.success("done"));
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persistBatchWithPreExecuted(ctx, allCalls, results, history, "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);

        verify(agentStore).saveWithSteps(cpCaptor.capture(), eq(history));
        String decisions = cpCaptor.getValue().getBatchToolDecisions();
        @SuppressWarnings("unchecked")
        Map<String, String> decoded = objectMapper.readValue(decisions, Map.class);
        assertEquals(ToolDecision.APPROVED.name(), decoded.get("search"));
        assertEquals(ToolDecision.PENDING.name(), decoded.get("read"));
    }

    @Test
    void persistBatchWithPreExecuted_CompositeNameExcludesExecutedTools() {
        var ctx = ctx();
        var allCalls = List.of(
                ParallelToolCall.pending("call_0", "search", Map.of(), "{}"),
                ParallelToolCall.pending("call_1", "read", Map.of(), "{}"));
        Map<String, ToolResponse> results = Map.of("search", ToolResponse.success("done"));
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        manager.persistBatchWithPreExecuted(ctx, allCalls, results, history, "telegram", "12345");

        verify(agentStore).saveWithSteps(cpCaptor.capture(), eq(history));
        String compositeName = cpCaptor.getValue().getPendingToolName();
        assertFalse(compositeName.contains("search"), "composite name should exclude pre-executed 'search'");
        assertTrue(compositeName.contains("read"), "composite name should include pending 'read'");
    }

    // ── Task #7: cover previously-missed reachable branches (serialization fallbacks, CLI channel,
    //    audio media, malformed JSON, and the trace/domain-event publish paths) ──

    private static Map<String, Object> selfReferencingMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("self", m); // Jackson FAIL_ON_SELF_REFERENCES throws on serialization
        return m;
    }

    @Test
    void persist_WithFrame_SerializationFallbackOnUnserializableParams() {
        var ctx = ctx();
        var badFrame = new ApprovalFrame("search_tool", selfReferencingMap(), RiskLevel.MEDIUM);
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persist(ctx, badFrame, List.of(), "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval); // fallback is non-fatal
    }

    @Test
    void persist_WithStringOverload_HandlesMalformedJson() {
        var ctx = ctx();
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persist(ctx, "search_tool", "{not-valid-json", history, "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval); // parse failure falls back to empty params
    }

    @Test
    void persist_WithFrame_StampsCliChannelForCliSession() {
        var cliCtx = new AgentExecutionContext(
                AgentSentinels.CLI_SESSION_PREFIX + "abc",
                "user-1",
                "chat-1",
                "coordinator",
                "do task",
                AutonomyLevel.SUPERVISED,
                null,
                5,
                0,
                null, // channelType blank -> CLI fallback applies
                null,
                null,
                null);
        when(agentStore.load(cliCtx.sessionId(), "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        manager.persist(cliCtx, frame(), List.of(), null, null);

        verify(agentStore).saveWithSteps(cpCaptor.capture(), anyList());
        assertEquals("cli", cpCaptor.getValue().getChannelType());
        assertEquals("cli", cpCaptor.getValue().getChannelPlatformId());
    }

    @Test
    void populateContextData_FlagsInboundAudioFromMediaAttachments() {
        var ctx = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "do task",
                AutonomyLevel.SUPERVISED,
                null,
                5,
                0,
                "telegram",
                "12345",
                null,
                null,
                List.of(MediaAttachment.ofInline(
                        "a1", AttachmentType.AUDIO, "audio/ogg", "voice.ogg", new byte[] {1, 2, 3}, Map.of())));
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        manager.persist(ctx, frame(), List.of(), "telegram", "12345");

        verify(agentStore).saveWithSteps(cpCaptor.capture(), anyList());
        String contextDataJson = cpCaptor.getValue().getContextDataJson();
        assertNotNull(contextDataJson);
        assertTrue(contextDataJson.contains(TurnDispatchService.DATA_INBOUND_AUDIO));
    }

    @Test
    void populateContextData_SerializationFallbackOnUnserializableData() {
        var ctx = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "do task",
                AutonomyLevel.SUPERVISED,
                null,
                5,
                0,
                "telegram",
                "12345",
                null,
                selfReferencingMap(),
                List.of());
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persist(ctx, frame(), List.of(), "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
    }

    @Test
    void persistDelegatedWait_ReturnsFalseOnSerializationFailure() {
        var context = ctx();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());

        boolean ok = manager.persistDelegatedWait(
                context, ToolCapability.DELEGATE_TO_AGENT.toolName(), selfReferencingMap(), List.of());

        assertFalse(ok);
    }

    @Test
    void persistBatch_SerializationFallbackOnUnserializableParams() {
        var ctx = ctx();
        var calls = List.of(ParallelToolCall.pending("call_0", "web_search", selfReferencingMap(), "{}"));
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persistBatch(ctx, calls, List.of(), "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
    }

    @Test
    void persistBatchWithPreExecuted_SerializationFallbackOnUnserializableParams() {
        var ctx = ctx();
        var calls = List.of(ParallelToolCall.pending("call_0", "search", selfReferencingMap(), "{}"));
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = manager.persistBatchWithPreExecuted(ctx, calls, Map.of(), List.of(), "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
    }

    @Test
    void persist_WithFrame_PublishesTraceAndDomainEventWhenWired() {
        when(eventBusInstance.isResolvable()).thenReturn(true);
        when(eventBusInstance.get()).thenReturn(domainEventBus);
        var wired = new ApprovalCheckpointManager(
                agentStore, agentSseService, objectMapper, tracePublisher, eventBusInstance);

        var ctx = ctx();
        var history = List.<ReasoningStep>of();
        when(agentStore.load("sess-1", "coordinator")).thenReturn(Optional.empty());
        when(agentStore.saveWithSteps(any(), anyList())).thenAnswer(inv -> inv.getArgument(0));

        AgentResult result = wired.persist(ctx, frame(), history, "telegram", "12345");

        assertTrue(result instanceof AgentResult.AwaitingApproval);
        verify(tracePublisher).publish(any());
        verify(domainEventBus).publish(any(), any());
    }
}
