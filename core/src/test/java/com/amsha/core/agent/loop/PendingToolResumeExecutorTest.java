package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.message.UserMessages;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingToolResumeExecutorTest {

    @Mock
    StatefulAgentStore agentStore;

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ExecutionFailureRecoveryService recoveryService;

    @Mock
    PersonaEscalationService escalationService;

    @Mock
    AgentStateMachineRunner stateMachineRunner;

    @Mock
    AgentCheckpoint checkpoint;

    @Mock
    ToolInvocationGuard guard;

    private PendingToolResumeExecutor executor;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executor = new PendingToolResumeExecutor(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
                guard);
        lenient()
                .when(guard.evaluate(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
    }

    @Test
    void testExecute_nullPendingToolName_completesCheckpointAndRunsFinishPassWithoutNpe() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coder",
                "Hi",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "WEBSOCKET",
                "p1",
                null,
                Map.of());

        when(checkpoint.getPendingToolName()).thenReturn(null);
        when(stateMachineRunner.runFinishPass(any(), any(), any()))
                .thenReturn(AgentResult.finished("Hello! How can I help you?", List.of()));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "WEBSOCKET", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("Hello"));
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
        verify(agentStore).save(checkpoint);
    }

    @Test
    void testExecute_blankPendingToolName_completesCheckpointAndRunsFinishPassWithoutNpe() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "coordinator",
                "Hi",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "WEBSOCKET",
                "p1",
                null,
                Map.of());

        when(checkpoint.getPendingToolName()).thenReturn("   ");
        when(stateMachineRunner.runFinishPass(any(), any(), any()))
                .thenReturn(AgentResult.finished("Hello! How can I help you?", List.of()));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "WEBSOCKET", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("Hello"));
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
        verify(agentStore).save(checkpoint);
    }

    @Test
    void testExecute_delegateResumeStripsInternalDirectiveTail() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s-delegate",
                "u1",
                "c1",
                "coordinator",
                "delegate task",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "CLI",
                "u1",
                null,
                Map.of());
        when(checkpoint.getPendingToolName()).thenReturn("garuda");
        when(checkpoint.getPendingToolParametersJson()).thenReturn("{}");
        when(toolRegistry.invokeTool(any(), any(), any()))
                .thenReturn(ToolResponse.success(
                        "Safe delegation status.\n\nCRITICAL DIRECTIVE: Do NOT retry. FINISH now."));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "CLI", stateMachineRunner);

        assertEquals("Safe delegation status.", ((AgentResult.Finished) result).answer());
    }

    @Test
    void testExecute_scheduleTaskResumeCompletesDirectlyWithoutFinishPass() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s-sched",
                "u1",
                "c1",
                "coordinator",
                "schedule a task",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "CLI",
                "u1",
                null,
                Map.of());
        when(checkpoint.getPendingToolName()).thenReturn("schedule_task");
        when(checkpoint.getPendingToolParametersJson()).thenReturn("{}");
        when(toolRegistry.invokeTool(any(), any(), any()))
                .thenReturn(
                        ToolResponse.success(
                                "Task scheduled successfully. ID: 123 | Next fire: 2026-09-02T16:00:00Z\n\nCRITICAL DIRECTIVE: FINISH now."));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "CLI", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals(
                "✅ Task scheduled successfully. ID: 123 | Next fire: 2026-09-02T16:00:00Z",
                ((AgentResult.Finished) result).answer());
        verify(checkpoint).setStatus(CheckpointStatus.IN_PROGRESS);
    }

    @Test
    void testExecute_delegationPausedReturnsAwaitingApprovalWithoutCompletingCheckpoint() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s-cron",
                "u1",
                "c1",
                "researcher",
                "run delegated task",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "u1",
                null,
                Map.of());
        when(checkpoint.getPendingToolName()).thenReturn("garuda");
        when(checkpoint.getPendingToolParametersJson()).thenReturn("{}");
        String pausedObs = UserMessages.delegationPaused("brahma", "sandbox_exec");
        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.success(pausedObs));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "TELEGRAM", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        AgentResult.AwaitingApproval aa = (AgentResult.AwaitingApproval) result;
        assertEquals("sandbox_exec", aa.toolName());
        assertEquals("s-cron", aa.sessionId());
        verify(checkpoint, Mockito.never()).setStatus(CheckpointStatus.COMPLETED);
    }

    @Test
    void testExecute_delegationPausedWithParamsPropagatesParametersToApprovalFrame() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s-cron",
                "u1",
                "c1",
                "coordinator",
                "run delegated task",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "u1",
                null,
                Map.of());
        when(checkpoint.getPendingToolName()).thenReturn("garuda");
        when(checkpoint.getPendingToolParametersJson()).thenReturn("{}");
        String pausedObs = UserMessages.delegationPaused(
                "saraswati", "web_fetch", "{\"url\":\"https://spring.io/projects/release-highlights\"}");
        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.success(pausedObs));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "TELEGRAM", stateMachineRunner);

        assertNotNull(result);
        assertInstanceOf(AgentResult.AwaitingApproval.class, result);
        AgentResult.AwaitingApproval aa = (AgentResult.AwaitingApproval) result;
        assertEquals("web_fetch", aa.toolName());
        assertEquals("s-cron", aa.sessionId());
        assertEquals(
                "https://spring.io/projects/release-highlights",
                aa.frame().params().get("url"));
        assertTrue(aa.paramsJson().contains("https://spring.io/projects/release-highlights"));
        verify(checkpoint, Mockito.never()).setStatus(CheckpointStatus.COMPLETED);
    }

    @Test
    void testExecute_failsClosedWhenMandatoryGuardIsUnavailable() {
        executor = new PendingToolResumeExecutor(
                agentStore,
                toolRegistry,
                objectMapper,
                reasoningEngine,
                recoveryService,
                escalationService,
                new ToolDelegationResolver(),
                null);
        AgentExecutionContext ctx = new AgentExecutionContext(
                "s-guard",
                "u1",
                "c1",
                "coordinator",
                "fetch",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "CLI",
                "u1",
                null,
                Map.of());
        when(checkpoint.getPendingToolName()).thenReturn("web_fetch");
        when(checkpoint.getPendingToolParametersJson()).thenReturn("{\"url\":\"https://example.com\"}");

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "CLI", stateMachineRunner);

        assertInstanceOf(AgentResult.Failed.class, result);
        verify(toolRegistry, never()).invokeTool(any(), any(), any());
    }

    @Test
    void testExecutePendingToolBrowserFailureFallsBackToWebFetch() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "sess-browser",
                "u1",
                "c1",
                "researcher",
                "browse page",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "CLI",
                "u1",
                null,
                Map.of());
        when(checkpoint.getPendingToolName()).thenReturn(ToolCapability.BROWSER_NAVIGATE.toolName());
        when(checkpoint.getPendingToolParametersJson()).thenReturn("{\"url\":\"https://example.com\"}");
        when(toolRegistry.invokeTool(any(), any(), any())).thenReturn(ToolResponse.failure(502, "browser timeout"));
        when(stateMachineRunner.run(any(), any(), any()))
                .thenReturn(AgentResult.finished("Fetched by fallback", List.of()));

        AgentResult result = executor.execute(ctx, new ArrayList<>(), checkpoint, "CLI", stateMachineRunner);

        assertInstanceOf(AgentResult.Finished.class, result);
        verify(stateMachineRunner).run(any(AgentExecutionContext.class), eq(reasoningEngine), anyList());
    }
}
