package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.loop.GroupConsultationEngine;
import com.amsha.core.agent.loop.GroupConsultationService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentSentinels;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    GroupConsultationEngine groupConsultationEngine;

    @Mock
    GroupConsultationDelegator groupConsultationDelegator;

    @Mock
    RoleRegistry roleRegistry;

    @Mock
    Instance<VerificationGate> verificationGateInstance;

    @Mock
    VerificationGate verificationGate;

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
        Mockito.lenient().when(verificationGateInstance.isResolvable()).thenReturn(true);
        Mockito.lenient().when(verificationGateInstance.get()).thenReturn(verificationGate);
        tool.verificationGate = verificationGateInstance;

        Mockito.lenient().when(agentLoopInstance.get()).thenReturn(agentLoop);
        Mockito.lenient()
                .when(checkpointCrud.findBySessionAndRole(any(), any()))
                .thenReturn(Optional.empty());
        Mockito.lenient().when(roleRegistry.resolveRoleDefinition(any())).thenAnswer(invocation -> {
            String roleId = invocation.getArgument(0);
            return AgentRoleEnum.fromString(roleId).definition();
        });
    }

    @Test
    void testBasicProperties() {
        assertEquals("garuda", tool.name());
        assertNotNull(tool.description());
        assertFalse(tool.requiresSandbox());
        assertEquals(ToolOperation.DELEGATE_AUTONOMOUS, tool.requiredOperation());
        assertNotNull(tool.parametersSchema());
    }

    @Test
    void execute_skillRoleAttemptsDelegation() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "skill:jdb_debugger",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);

        ToolResponse res = tool.execute(ctx, Map.of("agent", "shiva", "goal", "debug"));
        assertTrue(res.success());
        assertTrue(res.output().contains("You are a skill role and must execute the task directly"));
    }

    @Test
    void execute_missingAgentOrGoal() {
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

        ToolResponse res1 = tool.execute(ctx, Map.of("goal", "debug"));
        assertFalse(res1.success());

        ToolResponse res2 = tool.execute(ctx, Map.of("agent", "shiva"));
        assertFalse(res2.success());
    }

    @Test
    void execute_selfDelegation() {
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

        ToolResponse res = tool.execute(ctx, Map.of("agent", "coordinator", "goal", "do coordination"));
        assertTrue(res.success());
        assertTrue(res.output().contains("You tried to delegate a task to yourself"));
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
                        anyInt(),
                        any(MessageContext.class)))
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

        ArgumentCaptor<MessageContext> contextCaptor = ArgumentCaptor.forClass(MessageContext.class);
        verify(agentLoop)
                .run(
                        startsWith(SESSION_ID + AgentSentinels.DELEGATE_SESSION_SENTINEL + "shiva"),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        eq("debug this"),
                        eq(reasoningEngine),
                        anyInt(),
                        contextCaptor.capture());
        assertEquals(CHAT_ID, contextCaptor.getValue().getChatId());
        assertEquals(USER_ID, contextCaptor.getValue().getUser().platformId());
        assertEquals("debug this", contextCaptor.getValue().getMessage().text());
    }

    @Test
    void execute_depthLimitReached() {
        String deepSession = SESSION_ID + AgentSentinels.DELEGATE_SESSION_SENTINEL + "shiva"
                + AgentSentinels.DELEGATE_SESSION_SENTINEL + "brahma"
                + AgentSentinels.DELEGATE_SESSION_SENTINEL + "saraswati";

        ToolExecutionContext ctx = new ToolExecutionContext(
                deepSession,
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

        ToolResponse response = tool.execute(ctx, Map.of("agent", "shiva", "goal", "do this"));
        assertTrue(response.success());
        assertTrue(response.output().contains("maximum depth of 3 reached"));
    }

    @Test
    void execute_verificationGateFailure_routesToShiva() {
        Map<String, Object> params = Map.of("agent", "brahma", "goal", "write code");

        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.CODER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("public class Bug {}", List.of()));

        VerificationGate.VerificationResult vFail =
                new VerificationGate.VerificationResult(false, "Compilation error in line 1");
        when(verificationGate.verify(any(), eq("public class Bug {}"))).thenReturn(vFail);

        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("Fixed class", List.of()));

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
        assertTrue(response.output().contains("Fixed class"));
    }

    @Test
    void execute_checkpointParentChildLinkage() {
        Map<String, Object> params = Map.of("agent", "shiva", "goal", "debug this");

        Instant now = Instant.now();
        CheckpointDto parentCp = new CheckpointDto(
                100L,
                SESSION_ID,
                "coordinator",
                "ACTIVE",
                "FULL",
                null,
                null,
                null,
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                2,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now);
        CheckpointDto childCp = new CheckpointDto(
                101L,
                "child-sess",
                "debugger",
                "ACTIVE",
                "FULL",
                null,
                null,
                null,
                "goal",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                10,
                2,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                now,
                now);

        when(checkpointCrud.findBySessionAndRole(eq(SESSION_ID), eq("coordinator")))
                .thenReturn(Optional.of(parentCp));
        when(checkpointCrud.findBySessionAndRole(contains("shiva"), eq("debugger")))
                .thenReturn(Optional.of(childCp));

        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("Done", List.of()));

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

        tool.execute(ctx, params);
        verify(checkpointCrud)
                .save(argThat(dto -> dto.parentCheckpointId() != null && dto.parentCheckpointId() == 100L));
    }
}
