package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrowserPreflightHandlerTest {

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ToolInvoker toolInvoker;

    @Mock
    ApprovalCheckpointManager checkpointManager;

    @Mock
    ToolInvocationGuard invocationGuard;

    @Mock
    AgentTool scheduleTool;

    private BrowserPreflightHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BrowserPreflightHandler(toolRegistry, toolInvoker, checkpointManager, invocationGuard);
        when(toolRegistry.findTool(ToolCapability.SCHEDULE_TASK.toolName())).thenReturn(Optional.of(scheduleTool));
        when(scheduleTool.isEnabled()).thenReturn(true);
    }

    @Test
    void explicitOneShotScheduleUsesToolWithoutReasoningPass() throws Exception {
        AgentExecutionContext context = context("Schedule task to check the server logs in 1 minute");
        when(invocationGuard.evaluate(
                        anyString(),
                        anyString(),
                        eq(AutonomyLevel.SUPERVISED),
                        eq(ToolCapability.SCHEDULE_TASK.toolName()),
                        anyMap(),
                        anyList(),
                        anyString(),
                        anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(toolInvoker.invoke(eq(ToolCapability.SCHEDULE_TASK.toolName()), anyMap(), eq("session-1")))
                .thenReturn(ToolResponse.success("Task scheduled successfully. ID: cron-1"));

        List<ReasoningStep> history = new ArrayList<>();
        Optional<AgentResult> result = handler.run(context, history);

        AgentResult.Finished finished = assertInstanceOf(AgentResult.Finished.class, result.orElseThrow());
        assertTrue(finished.answer().contains("Task scheduled successfully"));
        assertEquals(1, history.size());
        assertEquals(ToolCapability.SCHEDULE_TASK.toolName(), history.getFirst().action());
        assertEquals("check the server logs", history.getFirst().actionInput().get("goal"));
        assertEquals(Boolean.TRUE, history.getFirst().actionInput().get("runOnce"));
        verify(toolInvoker).invoke(eq(ToolCapability.SCHEDULE_TASK.toolName()), anyMap(), eq("session-1"));
    }

    @Test
    void supervisedSchedulePersistsApprovalBeforeToolInvocation() throws Exception {
        AgentExecutionContext context = context("Remind me in 1 minute to check the server logs");
        ApprovalFrame frame = new ApprovalFrame(
                ToolCapability.SCHEDULE_TASK.toolName(), Map.of("goal", "check the server logs"), RiskLevel.MEDIUM);
        when(invocationGuard.evaluate(
                        anyString(),
                        anyString(),
                        eq(AutonomyLevel.SUPERVISED),
                        eq(ToolCapability.SCHEDULE_TASK.toolName()),
                        anyMap(),
                        anyList(),
                        anyString(),
                        anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(frame));
        AgentResult awaiting = AgentResult.awaitingApproval(frame, "session-1", List.of());
        when(checkpointManager.persist(
                        any(AgentExecutionContext.class), eq(frame), anyList(), anyString(), anyString()))
                .thenReturn(awaiting);

        Optional<AgentResult> result = handler.run(context, new ArrayList<>());

        assertEquals(awaiting, result.orElseThrow());
        verify(checkpointManager)
                .persist(any(AgentExecutionContext.class), eq(frame), anyList(), anyString(), anyString());
        verify(toolInvoker, never()).invoke(anyString(), anyMap(), anyString());
    }

    private static AgentExecutionContext context(String goal) {
        return new AgentExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "coordinator",
                goal,
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                "WEBSOCKET",
                "platform-1",
                "",
                Map.of("SUGGESTED_CRON_SOURCE", "UTC"));
    }
}
