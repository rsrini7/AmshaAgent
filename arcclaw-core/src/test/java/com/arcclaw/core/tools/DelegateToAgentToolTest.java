package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.scope.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DelegateToAgentToolTest {

    @Mock ReasoningEngine reasoningEngine;
    @Mock AgentLoop agentLoop;
    @Mock Instance<AgentLoop> agentLoopInstance;

    @InjectMocks DelegateToAgentTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";
    private static final String CHAT_ID = "test-chat";

    @BeforeEach
    void setup() {
        Mockito.lenient().when(agentLoopInstance.get()).thenReturn(agentLoop);
    }

    @Test
    void execute_successfulDelegation() throws Exception {
        Map<String, Object> params = Map.of(
            "agent", "shiva",
            "goal", "debug this"
        );

        when(agentLoop.run(
            ArgumentMatchers.startsWith(SESSION_ID + ":d:debugger"),
            eq(AgentRoleEnum.DEBUGGER.toScopedName()),
            eq("debug this"),
            eq(reasoningEngine),
            anyInt() // 3
        )).thenReturn(AgentResult.finished("Fixed NPE", List.of()));

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(params));

        assertTrue(response.success());
        assertTrue(response.output().contains("Fixed NPE"));
    }

    @Test
    void execute_selfDelegationRejected() throws Exception {
        Map<String, Object> params = Map.of(
            "agent", "coordinator",
            "goal", "do this"
        );

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(params));

        assertFalse(response.success());
        assertTrue(response.output().contains("Cannot delegate to yourself"));
    }

    @Test
    void execute_unknownAgentRejected() throws Exception {
        Map<String, Object> params = Map.of(
            "agent", "random_guy",
            "goal", "do this"
        );

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(params));

        assertFalse(response.success());
        assertTrue(response.output().contains("Unknown agent"));
    }

    @Test
    void execute_handlesLoopExceeded() throws Exception {
        Map<String, Object> params = Map.of(
            "agent", "saraswati",
            "goal", "research this"
        );

        when(agentLoop.run(
            any(), any(), any(), any(), anyInt()
        )).thenReturn(AgentResult.loopExceeded(10, List.of()));

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(params));

        assertFalse(response.success());
        assertEquals(500, response.exitCode());
        assertTrue(response.output().contains("exceeded its iteration budget"));
    }

    @Test
    void execute_handlesAwaitingApproval() throws Exception {
        Map<String, Object> params = Map.of(
            "agent", "brahma",
            "goal", "code this"
        );

        when(agentLoop.run(
            any(), any(), any(), any(), anyInt()
        )).thenReturn(AgentResult.awaitingApproval("sandbox_exec", "{}", SESSION_ID + ":d:coder", List.of()));

        ToolResponse response = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(params));

        assertFalse(response.success());
        assertEquals(409, response.exitCode());
        assertTrue(response.output().contains("paused for human approval"));
    }

    @Test
    void execute_propagatesBudgetExceededException() throws Exception {
        Map<String, Object> params = Map.of(
            "agent", "shiva",
            "goal", "debug this"
        );

        when(agentLoop.run(
            any(), any(), any(), any(), anyInt()
        )).thenThrow(new BudgetExceededException("Budget exhausted"));

        assertThrows(BudgetExceededException.class, () -> {
            WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(params));
        });
    }

    @Test
    void execute_missingParameters() throws Exception {
        ToolResponse response1 = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(Map.of("goal", "do it")));
        assertFalse(response1.success());
        assertTrue(response1.output().contains("Parameter 'agent' is required."));

        ToolResponse response2 = WorkspaceContext.runWithFullAutonomy(SESSION_ID, USER_ID, CHAT_ID, "coordinator", () -> tool.execute(Map.of("agent", "shiva")));
        assertFalse(response2.success());
        assertTrue(response2.output().contains("Parameter 'goal' is required."));
    }
}
