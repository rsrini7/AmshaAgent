package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.sse.AgentSseService;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCheckpointRecord;
import com.arcclaw.core.governance.ApprovalFrame;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.ToolDecision;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApprovalCheckpointManagerTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    AgentSseService agentSseService;

    ObjectMapper objectMapper = new ObjectMapper();

    ApprovalCheckpointManager manager;

    ArgumentCaptor<AgentCheckpoint> cpCaptor;

    @BeforeEach
    void setUp() {
        manager = new ApprovalCheckpointManager(agentStore, agentSseService, objectMapper);
        cpCaptor = ArgumentCaptor.forClass(AgentCheckpoint.class);
    }

    private AgentExecutionContext ctx() {
        return new AgentExecutionContext("sess-1", "user-1", "chat-1", "coordinator",
                "do task", AutonomyLevel.SUPERVISED,
                null, 5, 0, "telegram", "12345", null, null);
    }

    private ApprovalFrame frame() {
        return new ApprovalFrame("search_tool", Map.of("q", "hello"), ApprovalFrame.RiskLevel.MEDIUM);
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
        var ctx = new AgentExecutionContext("sess-1", "user-1", "chat-1", "coordinator",
                "do task", AutonomyLevel.SUPERVISED, null, 5, 0, null, null, null, null);
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
}
