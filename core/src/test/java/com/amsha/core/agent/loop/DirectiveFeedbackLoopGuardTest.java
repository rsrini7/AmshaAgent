package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.sse.AgentSseService;
import com.amsha.core.audit.AuditHashUtil;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.memory.tiered.DirectiveFeedbackPort;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

class DirectiveFeedbackLoopGuardTest {

    private AgentLoop loop;
    private StatefulAgentStore agentStore;
    private AgentStateMachineRunner stateMachineRunner;
    private CheckpointResumeHandler checkpointResumeHandler;
    private AuditHashUtil hashUtil;
    private SessionLifecycleCoordinator sessionLifecycleCoordinator;
    private CheckpointResumeCoordinator checkpointResumeCoordinator;
    private ObservabilityManager observabilityManager;
    private AgentSseService agentSseService;
    private MeterRegistry meterRegistry;
    private IntentClassificationService intentClassificationService;
    private DirectiveFeedbackPort directiveFeedbackPort;
    private Instance<DirectiveFeedbackPort> directiveFeedbackPortInstance;

    @BeforeEach
    void setUp() throws Exception {
        loop = new AgentLoop();
        agentStore = mock(StatefulAgentStore.class);
        stateMachineRunner = mock(AgentStateMachineRunner.class);
        checkpointResumeHandler = mock(CheckpointResumeHandler.class);
        hashUtil = mock(AuditHashUtil.class);
        sessionLifecycleCoordinator = mock(SessionLifecycleCoordinator.class);
        checkpointResumeCoordinator = mock(CheckpointResumeCoordinator.class);
        observabilityManager = mock(ObservabilityManager.class);
        agentSseService = mock(AgentSseService.class);
        meterRegistry = mock(MeterRegistry.class);
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        intentClassificationService = mock(IntentClassificationService.class);

        directiveFeedbackPort = mock(DirectiveFeedbackPort.class);
        directiveFeedbackPortInstance = mock(Instance.class);
        when(directiveFeedbackPortInstance.isUnsatisfied()).thenReturn(false);
        when(directiveFeedbackPortInstance.get()).thenReturn(directiveFeedbackPort);

        when(sessionLifecycleCoordinator.setupMdc(any(), any(), any())).thenReturn("user");
        when(checkpointResumeCoordinator.resolveResumePath(any(), any(), any(), any()))
                .thenReturn(new CheckpointResumeCoordinator.ResumeDecision.FreshStart());

        setField(loop, "agentStore", agentStore);
        setField(loop, "stateMachineRunner", stateMachineRunner);
        setField(loop, "checkpointResumeHandler", checkpointResumeHandler);
        setField(loop, "hashUtil", hashUtil);
        setField(loop, "sessionLifecycleCoordinator", sessionLifecycleCoordinator);
        Span span = mock(Span.class);
        Scope scope = mock(Scope.class);
        when(span.makeCurrent()).thenReturn(scope);
        when(observabilityManager.beginSession(any(), any(), any())).thenReturn(span);

        setField(loop, "checkpointResumeCoordinator", checkpointResumeCoordinator);
        setField(loop, "observabilityManager", observabilityManager);
        setField(loop, "agentSseService", agentSseService);
        setField(loop, "meterRegistry", meterRegistry);
        setField(loop, "intentClassificationService", intentClassificationService);
        setField(loop, "directiveFeedbackPortInstance", directiveFeedbackPortInstance);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void run_withFinishedResult_callsFeedbackPortWithSuccess() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));

        AgentResult finished = AgentResult.finished("Goal accomplished successfully", List.of());
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(finished);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.runWithRole("s1", "coordinator", "mygoal", null, mock(ReasoningEngine.class), 5, null, "user1");
            return null;
        });

        verify(directiveFeedbackPort, times(1)).recordSessionOutcome("s1", "user1", true);
    }

    @Test
    void run_withAwaitingApprovalResult_doesNotCallFeedbackPort() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));

        AgentResult approval = AgentResult.awaitingApproval("mytool", "{}", "s1", List.of());
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(approval);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.runWithRole("s1", "coordinator", "mygoal", null, mock(ReasoningEngine.class), 5, null, "user1");
            return null;
        });

        verify(directiveFeedbackPort, never()).recordSessionOutcome(anyString(), anyString(), anyBoolean());
    }

    @Test
    void run_withAwaitingRetryResult_doesNotCallFeedbackPort() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));

        AgentResult retry = AgentResult.awaitingRetry("Rate limit", 5000, List.of());
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(retry);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.runWithRole("s1", "coordinator", "mygoal", null, mock(ReasoningEngine.class), 5, null, "user1");
            return null;
        });

        verify(directiveFeedbackPort, never()).recordSessionOutcome(anyString(), anyString(), anyBoolean());
    }

    @Test
    void run_withFailedResult_callsFeedbackPortWithFailure() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));

        AgentResult failed =
                AgentResult.failed("Something went wrong", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        when(stateMachineRunner.run(any(), any(), any())).thenReturn(failed);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.runWithRole("s1", "coordinator", "mygoal", null, mock(ReasoningEngine.class), 5, null, "user1");
            return null;
        });

        verify(directiveFeedbackPort, times(1)).recordSessionOutcome("s1", "user1", false);
    }

    @Test
    void run_throwingException_callsFeedbackPortWithFailure() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));

        when(stateMachineRunner.run(any(), any(), any())).thenThrow(new RuntimeException("Transient DB error"));

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            loop.runWithRole("s1", "coordinator", "mygoal", null, mock(ReasoningEngine.class), 5, null, "user1");
            return null;
        });

        verify(directiveFeedbackPort, times(1)).recordSessionOutcome("s1", "user1", false);
    }

    @Test
    void run_throwingBudgetExceededException_callsFeedbackPortWithFailureAndRethrows() throws Exception {
        AgentCheckpoint checkpoint = mock(AgentCheckpointRecord.class);
        when(checkpoint.getStatus()).thenReturn(CheckpointStatus.IN_PROGRESS);
        when(agentStore.load(any(), any())).thenReturn(Optional.of(checkpoint));

        when(stateMachineRunner.run(any(), any(), any())).thenThrow(new BudgetExceededException("Out of budget"));

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            assertThrows(BudgetExceededException.class, () -> {
                loop.runWithRole("s1", "coordinator", "mygoal", null, mock(ReasoningEngine.class), 5, null, "user1");
            });
            return null;
        });

        verify(directiveFeedbackPort, times(1)).recordSessionOutcome("s1", "user1", false);
    }
}
