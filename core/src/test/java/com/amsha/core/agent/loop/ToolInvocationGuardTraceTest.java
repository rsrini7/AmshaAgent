package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolInvocationGuardTraceTest {

    private final TracePublisher tracePublisher = mock(TracePublisher.class);
    private final ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);

    private ToolInvocationGuard guard;
    private ToolRegistry mockToolRegistry;
    private CapabilityRegistry capabilityRegistry;

    @BeforeEach
    void setUp() {
        guard = new ToolInvocationGuard();
        var roleRegistry = mock(RoleRegistry.class);
        ObjectMapper objectMapper = new ObjectMapper();
        mockToolRegistry = mock(ToolRegistry.class);
        var mockInterceptors = mock(Instance.class);
        var toolRiskShadowEvaluation = mock(ToolRiskShadowEvaluationService.class);
        var postPolicyToolRiskDispatcher = mock(PostPolicyToolRiskDispatcher.class);
        capabilityRegistry = mock(CapabilityRegistry.class);
        when(capabilityRegistry.hasCapability(anyString(), anyString())).thenReturn(true);
        guard.autonomyPolicy = new AutonomyPolicy.Standard();
        guard.approvalLeaseStore = mock(ApprovalLeaseStore.class);
        when(guard.approvalLeaseStore.hasActiveLease(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(false);
        when(mockInterceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(mockInterceptors.stream()).thenReturn(Stream.empty());

        guard.roleRegistry = roleRegistry;
        guard.objectMapper = objectMapper;
        guard.toolRegistry = mockToolRegistry;
        guard.capabilityRegistry = capabilityRegistry;
        guard.interceptors = mockInterceptors;
        guard.toolRiskShadowEvaluation = toolRiskShadowEvaluation;
        guard.postPolicyToolRiskDispatcher = postPolicyToolRiskDispatcher;
        when(postPolicyToolRiskDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.NoOpinion<>(NoOpinionReason.MISSING_MODEL));
        when(postPolicyToolRiskDispatcher.evaluateResolution(any()))
                .thenAnswer(invocation -> PostPolicyToolRiskTestSupport.resolution(
                        postPolicyToolRiskDispatcher.evaluate(invocation.getArgument(0))));

        guard.dynamicToolAdmissionLookup = mock(Instance.class);
        when(guard.dynamicToolAdmissionLookup.isUnsatisfied()).thenReturn(true);

        var mockTool = mock(AgentTool.class);
        when(mockTool.idempotencyMode()).thenReturn(IdempotencyMode.IDEMPOTENT);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
        when(mockToolRegistry.findTool(any())).thenReturn(Optional.of(mockTool));

        guard.init();
        guard.tracePublisher = tracePublisher;
    }

    @Test
    void deniedToolEmitsGuardCheckDenied() {
        when(capabilityRegistry.hasCapability("researcher", "host_exec")).thenReturn(false);
        var shellTool = mock(AgentTool.class);
        when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(mockToolRegistry.findTool("host_exec")).thenReturn(Optional.of(shellTool));

        var decision = guard.evaluate(
                "s1",
                "researcher",
                AutonomyLevel.SUPERVISED,
                "host_exec",
                Map.of("command", "ls"),
                List.of(),
                "goal",
                List.of());

        Assertions.assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
        verify(tracePublisher).publish(captor.capture());
        assertEquals("DENIED", captor.getValue().status());
        assertEquals(TraceStage.GUARD_CHECK, captor.getValue().stage());
        assertEquals("host_exec", captor.getValue().detail());
    }

    @Test
    void allowedToolEmitsGuardCheckAllow() {
        var decision =
                guard.evaluate("s1", "coder", AutonomyLevel.FULL, "tool", Map.of(), List.of(), "goal", List.of());

        Assertions.assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
        verify(tracePublisher).publish(captor.capture());
        assertEquals("ALLOW", captor.getValue().status());
        assertEquals(TraceStage.GUARD_CHECK, captor.getValue().stage());
    }

    @Test
    void hitlRequiredToolEmitsGuardCheckHitlRequired() {
        var shellTool = mock(AgentTool.class);
        when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(mockToolRegistry.findTool("host_exec")).thenReturn(Optional.of(shellTool));

        var decision = guard.evaluate(
                "s1",
                "coder",
                AutonomyLevel.SUPERVISED,
                "host_exec",
                Map.of("command", "ls"),
                List.of(),
                "goal",
                List.of());

        Assertions.assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
        verify(tracePublisher).publish(captor.capture());
        assertEquals("HITL_REQUIRED", captor.getValue().status());
        assertEquals(TraceStage.GUARD_CHECK, captor.getValue().stage());
        assertEquals(RiskLevel.CRITICAL.name(), captor.getValue().riskLevel());
    }
}
