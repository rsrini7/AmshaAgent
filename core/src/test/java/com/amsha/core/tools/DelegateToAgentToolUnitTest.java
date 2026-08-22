package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DelegateToAgentToolUnitTest {

    @Mock
    AuditService auditService;

    @Mock
    AgentOtelContext otelContext;

    @Mock
    DelegationProcessor delegationProcessor;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    Instance<AgentLoop> agentLoop;

    @Mock
    AgentLoop mockAgentLoop;

    @Mock
    CheckpointCrudPort checkpointCrud;

    @Mock
    GroupConsultationService groupConsultationService;

    @Mock
    GroupConsultationEngine groupConsultationEngine;

    @Mock
    GroupAnalyticsService groupAnalytics;

    @Mock
    TaskClassifierService taskClassifier;

    @Mock
    RoleRegistry roleRegistry;

    @Mock
    Instance<VerificationGate> verificationGate;

    @InjectMocks
    DelegateToAgentTool tool;

    @BeforeEach
    void setUp() {
        when(agentLoop.get()).thenReturn(mockAgentLoop);
        lenient().when(delegationProcessor.getDelegatableAgentList()).thenReturn("shiva, brahma");
        lenient().when(checkpointCrud.findBySessionAndRole(any(), any())).thenReturn(Optional.empty());
        lenient().when(verificationGate.isResolvable()).thenReturn(false);
    }

    @Test
    void testBasicProperties() {
        assertEquals("garuda", tool.name());
        assertFalse(tool.requiresSandbox());
        assertEquals(ToolOperation.DELEGATE_AUTONOMOUS, tool.requiredOperation());
        assertNotNull(tool.parametersSchema());
        assertNotNull(tool.description());
    }

    @Test
    void testExecuteSkillRoleBlocked() {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.agentRole()).thenReturn("skill:host_exec");
        when(ctx.sessionId()).thenReturn("sess-1");

        ToolResponse res = tool.execute(ctx, Map.of("agent", "shiva", "goal", "debug"));
        assertTrue(res instanceof ToolResponse.ToolSuccess);
        assertTrue(res.output().contains("skill role"));
    }

    @Test
    void testExecuteMissingAgentOrGoal() {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.agentRole()).thenReturn("coordinator");
        when(ctx.sessionId()).thenReturn("sess-1");

        ToolResponse res1 = tool.execute(ctx, Map.of("goal", "debug"));
        assertTrue(res1 instanceof ToolResponse.ToolFailure);

        ToolResponse res2 = tool.execute(ctx, Map.of("agent", "shiva"));
        assertTrue(res2 instanceof ToolResponse.ToolFailure);
    }

    @Test
    void testExecuteMaxDepthReached() {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.agentRole()).thenReturn("coordinator");
        String s = AgentSentinels.DELEGATE_SESSION_SENTINEL;
        when(ctx.sessionId()).thenReturn("sess" + s + "a1" + s + "a2" + s + "a3");

        ToolResponse res = tool.execute(ctx, Map.of("agent", "shiva", "goal", "debug"));
        assertTrue(res instanceof ToolResponse.ToolSuccess);
        assertTrue(res.output().contains("maximum depth"));
    }

    @Test
    void testExecuteSelfDelegationNormalizedNull() {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.agentRole()).thenReturn("coordinator");
        when(ctx.sessionId()).thenReturn("sess-1");
        when(delegationProcessor.normalizeAgent("coordinator", "debug", "coordinator"))
                .thenReturn(null);

        ToolResponse res = tool.execute(ctx, Map.of("agent", "coordinator", "goal", "debug"));
        assertTrue(res instanceof ToolResponse.ToolSuccess);
        assertTrue(res.output().contains("delegate a task to yourself"));
    }

    @Test
    void testNormalDelegationSuccess() {
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        when(ctx.agentRole()).thenReturn("coordinator");
        when(ctx.sessionId()).thenReturn("sess-1");
        when(ctx.userId()).thenReturn("user-1");
        when(ctx.chatId()).thenReturn("chat-1");
        when(ctx.autonomyLevel()).thenReturn(AutonomyLevel.FULL);

        when(delegationProcessor.normalizeAgent(anyString(), anyString(), anyString()))
                .thenReturn("shiva");
        RoleDefinition shivaDef = mock(RoleDefinition.class);
        when(shivaDef.maxIterations()).thenReturn(5);
        when(roleRegistry.resolveRoleDefinition(anyString())).thenReturn(shivaDef);

        AgentResult finished = AgentResult.finished("Code debugged successfully", List.of());
        when(mockAgentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(finished);
        when(delegationProcessor.handleSubAgentResult(any(), any()))
                .thenReturn(ToolResponse.success("Code debugged successfully"));

        ToolResponse res = tool.execute(ctx, Map.of("agent", "shiva", "goal", "debug code"));
        assertTrue(res instanceof ToolResponse.ToolSuccess);
        assertEquals("Code debugged successfully", res.output());
    }
}
