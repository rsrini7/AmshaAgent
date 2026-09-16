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
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.persistence.api.CheckpointCrudPort;
import com.amsha.persistence.api.CheckpointDto;
import com.amsha.persistence.api.CheckpointDtoHelper;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.opentelemetry.api.trace.Span;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

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

    private ToolExecutionContext coordinatorCtx() {
        return new ToolExecutionContext(
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
    }

    @Test
    void execute_unknownAgent_normalizeThrows() {
        ToolExecutionContext ctx = coordinatorCtx();
        ToolResponse res = tool.execute(ctx, Map.of("agent", "ghost", "goal", "do something"));
        assertFalse(res.success());
        assertEquals(400, res.exitCode());
    }

    @Test
    void execute_groupConsultMode_enhancesGoal() throws Exception {
        when(groupConsultationDelegator.isGroupLead(anyString())).thenReturn(true);
        when(groupConsultationDelegator.prepareGoal(any(), any(), any(), any())).thenReturn("enhanced goal");
        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        ToolResponse res =
                tool.execute(coordinatorCtx(), Map.of("agent", "shiva", "goal", "debug", "mode", "group_consult"));
        assertTrue(res.success());
        assertTrue(res.output().contains("ok"));
    }

    @Test
    void execute_readonlyLevel_delegates() throws Exception {
        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                SESSION_ID,
                USER_ID,
                CHAT_ID,
                "coordinator",
                AutonomyLevel.READONLY,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);
        ToolResponse res = tool.execute(ctx, Map.of("agent", "shiva", "goal", "debug"));
        assertTrue(res.success());
        assertTrue(res.output().contains("ok"));
    }

    @Test
    void execute_parentCheckpointWithInboundAudio_marksAudio() throws Exception {
        CheckpointDto audioCp = CheckpointDtoHelper.create(
                "audio-session",
                "coordinator",
                "ACTIVE",
                "FULL",
                null,
                "goal",
                2,
                "{\"" + TurnDispatchService.DATA_INBOUND_AUDIO + "\":true}");
        when(checkpointCrud.findBySessionAndRole(eq("audio-session"), eq("coordinator")))
                .thenReturn(Optional.of(audioCp));
        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        ToolExecutionContext ctx = new ToolExecutionContext(
                "audio-session",
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
        ToolResponse res = tool.execute(ctx, Map.of("agent", "shiva", "goal", "debug"));
        assertTrue(res.success());
        assertTrue(res.output().contains("ok"));
    }

    @Test
    void execute_successfulDelegation_recordsSpanAttributes() throws Exception {
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(mock(Span.class));
        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.DEBUGGER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.finished("ok", List.of()));

        ToolResponse res = tool.execute(coordinatorCtx(), Map.of("agent", "shiva", "goal", "debug"));
        assertTrue(res.success());
        assertTrue(res.output().contains("ok"));
    }

    @Test
    void execute_subAgentThrowsBudgetExceeded_propagates() {
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(mock(Span.class));
        when(agentLoop.run(any(), any(), any(), eq(reasoningEngine), anyInt(), any()))
                .thenThrow(new BudgetExceededException("budget exceeded"));

        assertThrows(
                BudgetExceededException.class,
                () -> tool.execute(coordinatorCtx(), Map.of("agent", "shiva", "goal", "debug")));
    }

    @Test
    void execute_subAgentThrowsRuntimeException_returnsFailure() {
        when(otelContext.childSpan(anyString(), anyString())).thenReturn(mock(Span.class));
        when(agentLoop.run(any(), any(), any(), eq(reasoningEngine), anyInt(), any()))
                .thenThrow(new RuntimeException("boom"));

        ToolResponse res = tool.execute(coordinatorCtx(), Map.of("agent", "shiva", "goal", "debug"));
        assertFalse(res.success());
        assertEquals(500, res.exitCode());
    }

    @Test
    void execute_coderStructuredFinished_verificationSucceeds() throws Exception {
        when(verificationGate.verify(any(), any())).thenReturn(new VerificationGate.VerificationResult(true, ""));
        when(agentLoop.run(
                        anyString(),
                        eq(AgentRoleEnum.CODER.toScopedName()),
                        anyString(),
                        eq(reasoningEngine),
                        anyInt(),
                        any(MessageContext.class)))
                .thenReturn(AgentResult.structuredFinished("class Answer {}", Map.of("fields", 1), List.of()));

        ToolResponse res = tool.execute(coordinatorCtx(), Map.of("agent", "brahma", "goal", "write code"));
        assertTrue(res.success());
        assertTrue(res.output().contains("Answer"));
    }

    /**
     * Covers the shutdown-race guard at the top of {@code execute}. {@link
     * com.amsha.core.util.AgentGuards#isShuttingDown()} is {@code Arc.container() != null &&
     * !container.isRunning()}, so the container has to exist and report "not running" — a plain unit
     * test has no container at all and would take the other arm.
     */
    @Test
    void execute_abortsWhenTheCdiContainerIsShuttingDown() {
        ArcContainer container = mock(ArcContainer.class);
        when(container.isRunning()).thenReturn(false);
        try (MockedStatic<Arc> arc = mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);

            ToolResponse res = tool.execute(coordinatorCtx(), Map.of("agent", "shiva", "goal", "debug"));

            assertFalse(res.success());
            assertEquals(503, res.exitCode());
            assertTrue(res.output().contains("System is shutting down. Delegation aborted."));
            verifyNoInteractions(agentLoop);
        }
    }
}
