package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResumeStrategyDispatcherUnitTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    @Mock
    AgentStateMachineRunner stateMachineRunner;

    @Mock
    ToolInvocationGuard guard;

    ResumeStrategyDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ResumeStrategyDispatcher(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
                guard);
    }

    @Test
    void testResolveResumeGoalNoCheckpoint() {
        when(agentStore.load("sess-1", "role-1")).thenReturn(Optional.empty());
        assertEquals(AgentSentinels.GOAL_RESUME_APPROVED, dispatcher.resolveResumeGoal("sess-1", "role-1"));
    }

    @Test
    void testResolveResumeGoalWithOriginalGoal() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getOriginalGoal()).thenReturn("original goal");
        when(agentStore.load("sess-1", "role-1")).thenReturn(Optional.of(cp));

        assertEquals("original goal", dispatcher.resolveResumeGoal("sess-1", "role-1"));
    }

    @Test
    void testResolveResumeGoalWithAllDeniedBatchDecisions() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.hasBatchToolDecisions()).thenReturn(true);
        when(cp.getBatchToolDecisions()).thenReturn("{\"t1\":\"DENIED\",\"t2\":\"DENIED\"}");
        assertEquals(AgentSentinels.GOAL_DENY_APPROVED, dispatcher.resolveResumeGoal(cp));
    }

    @Test
    void testHandleGoalDenyApproved() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.goal()).thenReturn(AgentSentinels.GOAL_DENY_APPROVED);
        when(ctx.sessionId()).thenReturn("sess-1");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);

        assertTrue(result instanceof AgentResult.Finished);
        verify(cp).setStatus(CheckpointStatus.FAILED);
    }

    private void mockContext(AgentExecutionContext ctx, String goal, String session) {
        when(ctx.goal()).thenReturn(goal);
        when(ctx.sessionId()).thenReturn(session);
        when(ctx.agentRole()).thenReturn("role-1");
        when(ctx.userId()).thenReturn("usr-1");
        when(ctx.chatId()).thenReturn("chat-1");
        when(ctx.autonomyLevel()).thenReturn(AutonomyLevel.FULL);
        when(ctx.maxIterations()).thenReturn(10);
    }

    @Test
    void testExecutePendingToolSuccess() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "run tool", "sess-tool");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn("test_tool");
        when(cp.getPendingToolParametersJson()).thenReturn("{\"param\":\"val\"}");

        when(toolRegistry.invokeTool(eq("test_tool"), eq("{\"param\":\"val\"}"), eq("sess-tool")))
                .thenReturn(ToolResponse.success("Tool succeeded"));
        when(stateMachineRunner.runFinishPass(any(), eq(reasoningEngine), anyList()))
                .thenReturn(AgentResult.finished("Final output", List.of()));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Finished);
        verify(cp).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    @Test
    void testDelegatedIngestResumeAddsOriginalToolFollowUp() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "ingest document", "sess-ingest");
        when(ctx.agentRole()).thenReturn("coder");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn(ToolCapability.INGEST_DOCUMENT.toolName());
        when(cp.getPendingToolParametersJson()).thenReturn("{\"path\":\"document.pdf\"}");
        when(toolRegistry.invokeTool(eq(ToolCapability.DELEGATE_TO_AGENT.toolName()), anyString(), eq("sess-ingest")))
                .thenReturn(ToolResponse.success("Coordinator ingested the document"));

        List<ReasoningStep> history = new ArrayList<>();
        AgentResult result = dispatcher.handle(ctx, history, mock(MessageContext.class), stateMachineRunner, cp);

        assertTrue(result instanceof AgentResult.Finished);
        assertTrue(history.stream()
                .anyMatch(step ->
                        step.observation() != null && step.observation().contains("document ingested successfully")));
    }

    @Test
    void testExecutePendingToolInfraFailure() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "run tool", "sess-infra");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn("test_tool");
        when(cp.getPendingToolParametersJson()).thenReturn("{}");

        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.infraFailure("Podman offline"));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Failed);
    }

    @Test
    void testExecutePendingToolDelegateToAgentSuccess() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "run tool", "sess-del");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn(ToolCapability.DELEGATE_TO_AGENT.toolName());
        when(cp.getPendingToolParametersJson()).thenReturn("{}");

        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.success("Subagent finished"));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Finished);
        assertEquals("Subagent finished", ((AgentResult.Finished) result).answer());
    }

    @Test
    void testExecutePendingToolWebFetchFailureFallback() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "fetch page", "sess-web");
        when(ctx.withGoal(any())).thenReturn(ctx);

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn(ToolCapability.WEB_FETCH.toolName());
        when(cp.getPendingToolParametersJson()).thenReturn("{\"url\":\"https://example.com\"}");

        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.failure(403, "403 Forbidden"));
        when(stateMachineRunner.run(any(), any(), any()))
                .thenReturn(AgentResult.finished("Fallback navigated", List.of()));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Finished);
    }

    @Test
    void testAutoHealSuccess() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "exec script", "sess-heal");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn("sandbox_exec");
        when(cp.getPendingToolParametersJson()).thenReturn("{\"code\":\"bad code\",\"language\":\"python\"}");

        ToolResponse initialFailure = ToolResponse.failure(1, "SyntaxError: invalid syntax");
        when(toolRegistry.invokeTool(
                        eq("sandbox_exec"), eq("{\"code\":\"bad code\",\"language\":\"python\"}"), eq("sess-heal")))
                .thenReturn(initialFailure);

        when(recoveryService.shouldAttemptRecovery(any(), eq("sandbox_exec"), eq(initialFailure)))
                .thenReturn(true);
        when(recoveryService.attemptRecovery(
                        any(), any(), any(), eq("bad code"), eq("python"), any(), any(), any(), any()))
                .thenReturn(ExecutionFailureRecoveryService.RecoveryResult.success("fixed code", 1));
        when(recoveryService.validateForExecution("fixed code", "python")).thenReturn(null);

        ToolResponse fixedSuccess = ToolResponse.success("Fixed output");
        when(toolRegistry.invokeTool(
                        eq("sandbox_exec"), eq("{\"code\":\"fixed code\",\"language\":\"python\"}"), eq("sess-heal")))
                .thenReturn(fixedSuccess);
        when(stateMachineRunner.runFinishPass(any(), eq(reasoningEngine), anyList()))
                .thenReturn(AgentResult.finished("Final healed output", List.of()));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Finished);
    }

    @Test
    void testEscalationSuccess() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "complex query", "sess-esc");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn("custom_tool");
        when(cp.getPendingToolParametersJson()).thenReturn("{}");

        ToolResponse initialFailure = ToolResponse.failure(1, "Complex error requires expert role");
        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(initialFailure);

        when(recoveryService.shouldAttemptRecovery(any(), any(), any())).thenReturn(false);
        when(escalationService.shouldAttemptEscalation(any(), eq("custom_tool"), eq(initialFailure)))
                .thenReturn(true);
        when(escalationService.determineEscalationTarget(any(), any(), any())).thenReturn("expert_role");
        when(escalationService.attemptEscalation(
                        any(), any(), any(), any(), eq("expert_role"), any(), any(), any(), any(), any(), any()))
                .thenReturn(PersonaEscalationService.EscalationResult.success("expert_role", "Escalation answer", 1));
        when(stateMachineRunner.runFinishPass(any(), eq(reasoningEngine), anyList()))
                .thenReturn(AgentResult.finished("Escalated response", List.of()));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Finished);
    }

    @Test
    void testExecutePendingToolGenericFailure() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "run tool", "sess-fail");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn("custom_tool");
        when(cp.getPendingToolParametersJson()).thenReturn("{}");

        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.failure(1, "Command failed"));
        when(recoveryService.shouldAttemptRecovery(any(), any(), any())).thenReturn(false);
        when(escalationService.shouldAttemptEscalation(any(), any(), any())).thenReturn(false);

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Failed);
    }

    @Test
    void testExecutePendingToolThrowsException() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        mockContext(ctx, "run tool", "sess-ex");

        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getPendingToolName()).thenReturn("custom_tool");
        when(cp.getPendingToolParametersJson()).thenReturn("{}");

        when(toolRegistry.invokeTool(any(), any(), any())).thenThrow(new RuntimeException("Unexpected error"));

        AgentResult result =
                dispatcher.handle(ctx, new ArrayList<>(), mock(MessageContext.class), stateMachineRunner, cp);
        assertTrue(result instanceof AgentResult.Failed);
        verify(cp).setStatus(CheckpointStatus.FAILED);
    }
}
