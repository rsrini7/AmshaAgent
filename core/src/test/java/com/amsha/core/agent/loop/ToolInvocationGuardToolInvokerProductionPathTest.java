package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tool.attempt.ActionFingerprintGenerator;
import com.amsha.core.tool.attempt.DurableToolAttemptGuard;
import com.amsha.core.tool.attempt.ToolTargetResolver;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptFingerprintFormat;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;

/**
 * Focused ToolStepHandler guard and replay tests using mocked persistence and registry
 * collaborators. Persisted-lock coverage through the real ToolStepHandler and ToolInvoker
 * wiring is provided by the app integration test.
 */
class ToolInvocationGuardToolInvokerProductionPathTest {

    private static final String WORKSPACE = "workspace-1";
    private static final String SESSION = "session-1";
    private static final String TOOL = "guarded_tool";

    private ToolRegistry toolRegistry;
    private ToolAttemptStorePort attemptStore;
    private ToolInvocationGuard guard;
    private ToolInvoker invoker;
    private ToolStepHandler handler;
    private AgentTool tool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        attemptStore = mock(ToolAttemptStorePort.class);
        tool = mock(AgentTool.class);

        Instance<ToolAttemptStorePort> stores = mock(Instance.class);
        when(stores.isUnsatisfied()).thenReturn(false);
        when(stores.isAmbiguous()).thenReturn(false);
        when(stores.get()).thenReturn(attemptStore);
        DurableToolAttemptGuard durableGuard =
                new DurableToolAttemptGuard(stores, new ActionFingerprintGenerator(), new ToolTargetResolver());

        RoleRegistry roleRegistry = mock(RoleRegistry.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        ApprovalLeaseStore leaseStore = mock(ApprovalLeaseStore.class);
        Instance<ToolGuardInterceptor> interceptors = mock(Instance.class);
        Instance<DynamicToolAdmissionLookup> dynamicLookups = mock(Instance.class);

        when(capabilityRegistry.hasCapability(anyString(), anyString())).thenReturn(true);
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(false);
        when(leaseStore.hasActiveLease(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(false);
        when(interceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(interceptors.stream()).thenReturn(Stream.empty());
        when(dynamicLookups.isUnsatisfied()).thenReturn(true);
        when(toolRegistry.findTool(TOOL)).thenReturn(Optional.of(tool));
        when(tool.idempotencyMode()).thenReturn(IdempotencyMode.AT_MOST_ONCE);
        when(tool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
        when(tool.validateParameters(any())).thenReturn(null);

        guard = new ToolInvocationGuard();
        guard.roleRegistry = roleRegistry;
        guard.capabilityRegistry = capabilityRegistry;
        guard.toolRegistry = toolRegistry;
        guard.approvalLeaseStore = leaseStore;
        guard.autonomyPolicy = new AutonomyPolicy.Standard();
        guard.interceptors = interceptors;
        guard.dynamicToolAdmissionLookup = dynamicLookups;
        guard.durableToolAttemptGuard = durableGuard;
        guard.objectMapper = new ObjectMapper();
        guard.init();

        invoker = new ToolInvoker();
        invoker.toolRegistry = toolRegistry;
        invoker.observationSanitizer = mock(ToolObservationSanitizer.class);
        AgentOtelContext otel = mock(AgentOtelContext.class);
        when(otel.startToolSpan(anyString(), anyString(), anyString())).thenReturn(Span.getInvalid());
        invoker.agentOtelContext = otel;
        invoker.registry = new SimpleMeterRegistry();
        invoker.toolEvent = mock(Event.class);

        handler = new ToolStepHandler(
                mock(Instance.class),
                guard,
                invoker,
                mock(ChatMemoryWriter.class),
                mock(ApprovalCheckpointManager.class),
                mock(ParallelToolStepExecutor.class),
                new SimpleMeterRegistry(),
                mock(ExecutionFailureRecoveryService.class),
                mock(ToolStepValidator.class),
                mock(ToolStepEscalationHandler.class));
    }

    @Test
    void lockedAttemptNeverReachesExecutor() {
        when(attemptStore.findApplicableLocks(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(List.of(mock(DurableActionLockDto.class)));

        List<ReasoningStep> history = new ArrayList<>();
        AtomicReference<StepDecision> outcome = new AtomicReference<>();
        WorkspaceContext.runWithActiveWorkspace(
                WORKSPACE,
                () -> outcome.set(
                        handler.invokeToolAndObserve(context(), history, step(Map.of("arg", 1)), 0, List.of())));
        StepDecision decision = outcome.get();

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertEquals(1, history.size());
        assertFalse(history.getFirst().success());
        assertEquals(
                "Tool action is blocked by an unresolved prior execution.",
                history.getFirst().observation());
        verify(toolRegistry, never()).invokeToolWithOperationId(anyString(), anyMap(), anyString());
    }

    @Test
    void storeLookupFailureBlocksClosedAndNeverReachesExecutor() {
        when(attemptStore.findApplicableLocks(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("storage unavailable"));

        List<ReasoningStep> history = new ArrayList<>();
        AtomicReference<StepDecision> outcome = new AtomicReference<>();
        WorkspaceContext.runWithActiveWorkspace(
                WORKSPACE,
                () -> outcome.set(
                        handler.invokeToolAndObserve(context(), history, step(Map.of("arg", 1)), 0, List.of())));
        StepDecision decision = outcome.get();

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertFalse(history.getFirst().success());
        assertEquals(
                "Durable tool safety checks could not be completed.",
                history.getFirst().observation());
        verify(toolRegistry, never()).invokeToolWithOperationId(anyString(), anyMap(), anyString());
    }

    @Test
    void settledReplayTakesPrecedenceOverHistoryDedup() {
        when(attemptStore.findApplicableLocks(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(List.of());
        when(attemptStore.findAtMostOnceResult(anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(Optional.of(settledSuccess("stored outcome")));
        guard.recordToolCall(SESSION, TOOL, Map.of("arg", 1), "cached dedup outcome");

        List<ReasoningStep> history = new ArrayList<>();
        AtomicReference<StepDecision> outcome = new AtomicReference<>();
        WorkspaceContext.runWithActiveWorkspace(
                WORKSPACE,
                () -> outcome.set(
                        handler.invokeToolAndObserve(context(), history, step(Map.of("arg", 1)), 0, List.of())));
        StepDecision decision = outcome.get();

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(1, history.size());
        assertEquals("stored outcome", history.getFirst().observation());
        verify(toolRegistry, never()).invokeToolWithOperationId(anyString(), anyMap(), anyString());
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                SESSION,
                "user1",
                "chat1",
                "researcher",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                "TELEGRAM",
                "chat1",
                null,
                Map.of());
    }

    private ReasoningStep step(Map<String, Object> params) {
        return ReasoningStep.pending("thought", TOOL, params);
    }

    private ToolAttemptDto settledSuccess(String observation) {
        Instant now = Instant.now();
        return new ToolAttemptDto(
                WORKSPACE,
                SESSION,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1,
                TOOL,
                ToolAttemptFingerprintFormat.ACTION_V1_PREFIX + "b".repeat(64),
                1,
                null,
                ToolAttemptIdempotencyMode.AT_MOST_ONCE,
                ToolAttemptStatus.SETTLED_SUCCESS,
                0L,
                "BUILT_IN",
                null,
                null,
                null,
                observation,
                now,
                now,
                now,
                now);
    }
}
