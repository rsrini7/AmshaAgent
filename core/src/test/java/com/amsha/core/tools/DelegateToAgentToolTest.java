package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.GroupAnalyticsService;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.loop.TaskClassifierService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.persistence.api.CheckpointCrudPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S1192")
class DelegateToAgentToolTest {

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    AgentLoop agentLoop;

    @Mock
    Instance<AgentLoop> agentLoopInstance;

    @Mock
    CheckpointCrudPort checkpointCrud;

    @Mock
    AuditService auditService;

    @Mock
    AgentOtelContext otelContext;

    @Mock
    GroupConsultationService groupConsultationService;

    @InjectMocks
    DelegateToAgentTool tool;

    private static final String SESSION_ID = "test-session";
    private static final String USER_ID = "test-user";
    private static final String CHAT_ID = "test-chat";

    @BeforeEach
    void setup() {
        DelegationProcessor dp = new DelegationProcessor();
        dp.groupConsultationService = groupConsultationService;
        tool.delegationProcessor = dp;
        tool.groupConsultationService = groupConsultationService;

        Mockito.lenient().when(agentLoopInstance.get()).thenReturn(agentLoop);
        Mockito.lenient()
                .when(checkpointCrud.findBySessionAndRole(any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void execute_successfulDelegation() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "shiva",
                "goal", "debug this");

        when(agentLoop.run(
                        ArgumentMatchers.startsWith(SESSION_ID + AgentSentinels.DELEGATE_SESSION_SENTINEL + "shiva"),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        eq("debug this"),
                        eq(reasoningEngine),
                        anyInt()))
                .thenReturn(AgentResult.finished("Fixed NPE", List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, params);

        assertTrue(response.success());
        assertTrue(response.output().contains("Fixed NPE"));
    }

    @Test
    void execute_selfDelegationRedirectsToCoordinator() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "coordinator",
                "goal", "do this");

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, params);

        // Self-delegation auto-redirects to coordinator → tool returns success
        // telling the LLM to proceed with its own tools (not an error).
        assertNotNull(response);
        assertTrue(response.success());
        assertTrue(response.output().contains("delegate") || response.output().contains("own tools"));
    }

    @Test
    void execute_unknownAgentRejected() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "random_guy",
                "goal", "do this");

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, params);

        assertFalse(response.success());
        assertTrue(response.output().contains("Unknown agent"));
    }

    @Test
    void execute_handlesLoopExceeded() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "saraswati",
                "goal", "research this");

        when(agentLoop.run(any(), any(), any(), any(), anyInt())).thenReturn(AgentResult.loopExceeded(10, List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, params);

        assertFalse(response.success());
        assertEquals(500, response.exitCode());
        assertTrue(response.output().contains("exceeded its iteration budget"));
    }

    @Test
    void execute_handlesAwaitingApproval() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "brahma",
                "goal", "code this");

        when(agentLoop.run(any(), any(), any(), any(), anyInt()))
                .thenReturn(AgentResult.awaitingApproval(
                        ToolCapability.SANDBOX_EXEC.toolName(),
                        "{}",
                        SESSION_ID + AgentSentinels.DELEGATE_SESSION_SENTINEL + "coder",
                        List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, params);

        assertFalse(response.success());
        assertEquals(409, response.exitCode());
        assertTrue(response.output().contains("approval"));
    }

    @Test
    void execute_propagatesBudgetExceededException() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "shiva",
                "goal", "debug this");

        when(agentLoop.run(any(), any(), any(), any(), anyInt()))
                .thenThrow(new BudgetExceededException("Budget exhausted"));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        assertThrows(BudgetExceededException.class, () -> {
            tool.execute(ctx, params);
        });
    }

    @Test
    void execute_capabilityEscalation_allowed() throws Exception {
        Map<String, Object> params = Map.of(
                "agent", "researcher",
                "goal", "search the web");

        when(agentLoop.run(any(), eq("researcher"), any(), any(), anyInt()))
                .thenReturn(AgentResult.finished("research results", List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "architect",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response = tool.execute(ctx, params);

        assertTrue(response.success(), "architect → researcher delegation should now be allowed");
    }

    @Test
    void execute_missingParameters() throws Exception {
        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse response1 = tool.execute(ctx, Map.of("goal", "do it"));
        assertFalse(response1.success());
        assertTrue(response1.output().contains("Parameter 'agent' is required."));

        ToolResponse response2 = tool.execute(ctx, Map.of("agent", "shiva"));
        assertFalse(response2.success());
        assertTrue(response2.output().contains("Parameter 'goal' is required."));
    }

    @Test
    void execute_groupConsultMode_enhancesGoalWhenMultiDomain() throws Exception {
        GroupConsultationService mockTriageService = Mockito.mock(GroupConsultationService.class);
        GroupConsultationService.TriageResult triageResult = GroupConsultationService.TriageResult.consult(
                List.of("sentinel", "optimizer"),
                "Task spans multiple domains",
                "=== KAILASA FAMILY HUDDLE ===\nConsult: Kartikeya (security), Ganesha (logic)");

        Mockito.lenient().doReturn(triageResult).when(mockTriageService).triage(any(), any());
        Mockito.doReturn(true).when(mockTriageService).isGroupLead(any());
        Mockito.doReturn(Optional.of(AgentGroupRegistry.KAILASA))
                .when(mockTriageService)
                .getGroup(any());

        GroupConsultationEngine mockEngine = Mockito.mock(GroupConsultationEngine.class);
        mockEngine.groupRegistry = new AgentGroupRegistry();

        Map<String, String> subTasks = Map.of("sentinel", "Analyze security vulnerability");
        Mockito.doReturn(subTasks).when(mockEngine).buildSubTasks(any(), any());

        GroupConsultationEngine.GroupConsultationResult consultResult =
                new GroupConsultationEngine.GroupConsultationResult(
                        "kailasa", Map.of("sentinel", "Analyzed vulnerability: looks safe"), null, 1, 0);
        Mockito.doReturn(consultResult).when(mockEngine).consultGroup(any(), any(), any(), any());

        DelegateToAgentTool groupTool = new DelegateToAgentTool();
        groupTool.reasoningEngine = reasoningEngine;
        groupTool.agentLoop = agentLoopInstance;
        groupTool.checkpointCrud = checkpointCrud;
        groupTool.auditService = auditService;
        groupTool.otelContext = otelContext;
        groupTool.groupConsultationService = mockTriageService;
        groupTool.groupConsultationEngine = mockEngine;
        groupTool.taskClassifier = new TaskClassifierService();
        groupTool.groupAnalytics = new GroupAnalyticsService();
        groupTool.maxDelegationDepth = 3;

        DelegationProcessor dp = new DelegationProcessor();
        dp.groupConsultationService = mockTriageService;
        groupTool.delegationProcessor = dp;

        when(agentLoop.run(any(), any(), any(), any(), anyInt()))
                .thenReturn(AgentResult.finished("Consulted family", List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        Map<String, Object> params = Map.of(
                "agent", "shiva",
                "goal", "Fix SQL injection vulnerability and debug the recursive crash",
                "mode", "group_consult");

        ToolResponse response = groupTool.execute(ctx, params);
        assertTrue(response.success());
        Mockito.verify(mockTriageService).isGroupLead("debugger");
        assertEquals(1, groupTool.groupAnalytics.totalConsultations());
        assertEquals(1.0, groupTool.groupAnalytics.successRate("kailasa"));
    }
}
