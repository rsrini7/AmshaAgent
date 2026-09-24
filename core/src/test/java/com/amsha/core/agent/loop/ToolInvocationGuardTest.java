package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.amsha.classifier.api.ClassifierResult;
import com.amsha.classifier.api.ClassifierResult.NoOpinionReason;
import com.amsha.classifier.api.PostPolicyToolContext;
import com.amsha.classifier.api.ToolRiskAction;
import com.amsha.classifier.api.ToolRiskShadowContext;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolInvocationGuardTest {

    private ToolInvocationGuard guard;
    private RoleRegistry roleRegistry;
    private ObjectMapper objectMapper;
    private ToolRegistry mockToolRegistry;
    private Instance<ToolGuardInterceptor> mockInterceptors;
    private ToolRiskShadowEvaluationService toolRiskShadowEvaluation;
    private PostPolicyToolRiskDispatcher postPolicyToolRiskDispatcher;
    private CapabilityRegistry capabilityRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new ToolInvocationGuard();
        roleRegistry = mock(RoleRegistry.class);
        objectMapper = new ObjectMapper();
        mockToolRegistry = mock(ToolRegistry.class);
        mockInterceptors = mock(Instance.class);
        toolRiskShadowEvaluation = mock(ToolRiskShadowEvaluationService.class);
        postPolicyToolRiskDispatcher = mock(PostPolicyToolRiskDispatcher.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        when(capabilityRegistry.hasCapability(anyString(), anyString())).thenReturn(true);
        this.capabilityRegistry = capabilityRegistry;
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

        // Root Cause 1: Assume tool exists by default to allow hitl/autonomy tests to proceed
        var mockTool = mock(AgentTool.class);
        when(mockTool.idempotencyMode()).thenReturn(IdempotencyMode.IDEMPOTENT);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
        when(mockToolRegistry.findTool(any())).thenReturn(Optional.of(mockTool));
        when(mockToolRegistry.findTool("tool")).thenReturn(Optional.of(mockTool));

        guard.init(); // Initialize sortedInterceptors
    }

    @Test
    void postPolicyContextMarksPendingToolCallStepAsToolInvoked() {
        ReasoningStep pending = ReasoningStep.pending("t", "search", Map.of(), 0, 0);
        when(postPolicyToolRiskDispatcher.evaluate(any())).thenAnswer(invocation -> {
            PostPolicyToolContext ctx = invocation.getArgument(0);
            assertTrue(ctx.toolInvokedInTurn());
            return new ClassifierResult.NoOpinion<>(NoOpinionReason.MISSING_MODEL);
        });

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coder", AutonomyLevel.FULL, "tool", Map.of(), List.of(pending), "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
        verify(postPolicyToolRiskDispatcher).evaluateResolution(any());
    }

    @Test
    void testDuplicateToolCallReturnsCachedObservation() {
        ReasoningStep past =
                ReasoningStep.pending("thought", "tool", Map.of("arg", 1), 0, 0).withObservation("result 1");
        List<ReasoningStep> history = List.of(past);

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "tool", Map.of("arg", 1), history, "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Deduplicate.class, result);
        assertTrue(((ToolInvocationGuard.GuardDecision.Deduplicate) result)
                .cachedObservation()
                .contains("result 1"));
        verify(postPolicyToolRiskDispatcher, never()).evaluateResolution(any());
    }

    @Test
    void testFailedToolObservationIsNotCached() {
        ReasoningStep past = ReasoningStep.pending("thought", "tool", Map.of("arg", 1), 0, 0);
        // observation is null by default
        List<ReasoningStep> history = List.of(past);

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "tool", Map.of("arg", 1), history, "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, result);
    }

    @Test
    void testHitlPauseForApproval() {
        workspaceRun("s1", AutonomyLevel.SUPERVISED, () -> {
            when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1",
                    "coordinator",
                    AutonomyLevel.SUPERVISED,
                    "delete-all",
                    Map.of(),
                    List.of(),
                    "goal",
                    List.of());

            assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, result);
            verify(postPolicyToolRiskDispatcher, never()).evaluateResolution(any());
        });
    }

    @Test
    void postPolicyRiskCanOnlyUpgradeProvisionalAllowToApproval() {
        when(postPolicyToolRiskDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.Opinion<>(ToolRiskAction.REQUIRE_APPROVAL, 0.93f, "test-risk"));
        Map<String, Object> parameters = Map.of("path", "report.txt");

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.FULL, "tool", parameters, List.of(), "goal", List.of());

        ToolInvocationGuard.GuardDecision.PauseForApproval approval =
                assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, result);
        assertEquals("tool", approval.frame().tool());
        assertEquals(parameters, approval.frame().params());
        assertEquals(RiskLevel.LOW, approval.frame().riskLevel());
        verify(toolRiskShadowEvaluation, never())
                .observeProvisionalAllow(anyString(), any(ToolRiskShadowContext.class));
    }

    @Test
    void postPolicyRiskCanUpgradeProvisionalAllowToBlock() {
        when(postPolicyToolRiskDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.Opinion<>(ToolRiskAction.BLOCK, 0.99f, "test-block"));

        ToolInvocationGuard.GuardDecision result =
                guard.evaluate("s1", "coordinator", AutonomyLevel.FULL, "tool", Map.of(), List.of(), "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, result);
        verify(toolRiskShadowEvaluation, never())
                .observeProvisionalAllow(anyString(), any(ToolRiskShadowContext.class));
    }

    @Test
    void testFullAutonomyStillRequiresHitlForExecuteShell() {
        workspaceRun("s1", AutonomyLevel.FULL, () -> {
            var shellTool = mock(AgentTool.class);
            when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
            when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
            when(mockToolRegistry.findTool("delete-all")).thenReturn(Optional.of(shellTool));
            when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1", "coordinator", AutonomyLevel.FULL, "delete-all", Map.of(), List.of(), "goal", List.of());

            assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, result);
            verify(toolRiskShadowEvaluation).observeForDataCollection(anyString(), any(ToolRiskShadowContext.class));
            verify(toolRiskShadowEvaluation, never()).observeProvisionalAllow(anyString(), any());
        });
    }

    @Test
    void testFullAutonomySkipsHitlForNonShellTools() {
        workspaceRun("s1", AutonomyLevel.FULL, () -> {
            when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1", "coordinator", AutonomyLevel.FULL, "delete-all", Map.of(), List.of(), "goal", List.of());

            assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, result);
            verify(toolRiskShadowEvaluation).observeProvisionalAllow(anyString(), any(ToolRiskShadowContext.class));
        });
    }

    @Test
    void testInterceptorCanBlockTool() {
        ToolGuardInterceptor interceptor = mock(ToolGuardInterceptor.class);
        when(interceptor.handledToolNames()).thenReturn(Set.of("guarded-tool"));
        when(interceptor.evaluate(
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        any(),
                        anyList(),
                        anyString(),
                        anyList()))
                .thenReturn(Optional.of(new ToolInvocationGuard.GuardDecision.Blocked("interceptor-blocked")));

        when(mockInterceptors.iterator()).thenReturn(List.of(interceptor).iterator());
        when(mockInterceptors.stream()).thenReturn(Stream.of(interceptor));
        guard.init(); // Re-initialize with new interceptor mock

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "guarded-tool", Map.of(), List.of(), "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, result);
        assertTrue(((ToolInvocationGuard.GuardDecision.Blocked) result)
                .observation()
                .contains("interceptor-blocked"));
        verify(toolRiskShadowEvaluation, never()).observeProvisionalAllow(anyString(), any());
    }

    @Test
    void prePolicyAllowCannotBypassMandatoryHitl() {
        ToolGuardInterceptor interceptor = mock(ToolGuardInterceptor.class);
        when(interceptor.handledToolNames()).thenReturn(Set.of("tool"));
        when(interceptor.evaluate(
                        anyString(),
                        anyString(),
                        any(AutonomyLevel.class),
                        anyString(),
                        any(),
                        anyList(),
                        anyString(),
                        anyList()))
                .thenReturn(Optional.of(new ToolInvocationGuard.GuardDecision.Allow()));
        when(mockInterceptors.stream()).thenReturn(Stream.of(interceptor));
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);
        guard.init();

        ToolInvocationGuard.GuardDecision result = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "tool", Map.of(), List.of(), "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, result);
        verify(postPolicyToolRiskDispatcher, never()).evaluateResolution(any());
    }

    @Test
    void testApprovalGate_archivalInsert_requiresApprovalAtSupervisedLevel() {
        workspaceRun("s1", AutonomyLevel.SUPERVISED, () -> {
            // Create a real ArchivalMemoryInsertTool mock that returns requiresManualApproval=true
            var archivalInsertTool = mock(AgentTool.class);
            when(archivalInsertTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
            when(archivalInsertTool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
            when(archivalInsertTool.requiresManualApproval(any())).thenReturn(true);
            when(mockToolRegistry.findTool("archival_memory_insert")).thenReturn(Optional.of(archivalInsertTool));

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1",
                    "coordinator",
                    AutonomyLevel.SUPERVISED,
                    "archival_memory_insert",
                    Map.of("content", "test", "chunk_type", "FACT"),
                    List.of(),
                    "goal",
                    List.of());

            // At SUPERVISED level with requiresManualApproval=true, must pause for approval
            assertInstanceOf(
                    ToolInvocationGuard.GuardDecision.PauseForApproval.class,
                    result,
                    "ArchivalMemoryInsertTool must require approval at SUPERVISED autonomy");
        });
    }

    @Test
    void testApprovalGate_archivalInsert_allowedAtFullAutonomy() {
        workspaceRun("s1", AutonomyLevel.FULL, () -> {
            var archivalInsertTool = mock(AgentTool.class);
            when(archivalInsertTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
            when(archivalInsertTool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
            when(archivalInsertTool.requiresManualApproval(any())).thenReturn(true);
            when(mockToolRegistry.findTool("archival_memory_insert")).thenReturn(Optional.of(archivalInsertTool));

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1",
                    "coordinator",
                    AutonomyLevel.FULL,
                    "archival_memory_insert",
                    Map.of("content", "test", "chunk_type", "FACT"),
                    List.of(),
                    "goal",
                    List.of());

            // At FULL autonomy, approval is bypassed
            assertInstanceOf(
                    ToolInvocationGuard.GuardDecision.Allow.class,
                    result,
                    "ArchivalMemoryInsertTool should be allowed at FULL autonomy");
        });
    }

    @Test
    void testApprovalGate_coreMemoryAppend_noApprovalRequired() {
        workspaceRun("s1", AutonomyLevel.SUPERVISED, () -> {
            var coreAppendTool = mock(AgentTool.class);
            when(coreAppendTool.idempotencyMode()).thenReturn(IdempotencyMode.IDEMPOTENT);
            when(coreAppendTool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
            when(coreAppendTool.requiresManualApproval(any())).thenReturn(false);
            when(mockToolRegistry.findTool("core_memory_append")).thenReturn(Optional.of(coreAppendTool));
            when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(false);

            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "s1",
                    "coordinator",
                    AutonomyLevel.SUPERVISED,
                    "core_memory_append",
                    Map.of("block_key", "user_profile", "content", "test"),
                    List.of(),
                    "goal",
                    List.of());

            // Core memory append does NOT require manual approval
            assertInstanceOf(
                    ToolInvocationGuard.GuardDecision.Allow.class,
                    result,
                    "CoreMemoryAppendTool should not require approval at SUPERVISED");
        });
    }

    private void workspaceRun(String sessionId, AutonomyLevel level, Runnable runnable) {
        WorkspaceContext.run(sessionId, "user", "chat", level, "coordinator", runnable);
    }

    @Test
    void unauthorizedRoleBlockedBeforeHitlPauseEvenForAlwaysHitlOperation() {
        // F2 regression: a role without the capability grant must be Blocked up front.
        // Requesting HITL approval for an action policy forbids anyway wastes a human
        // approval cycle (dist incident: researcher + host_exec).
        when(capabilityRegistry.hasCapability("researcher", "host_exec")).thenReturn(false);
        var shellTool = mock(AgentTool.class);
        when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(mockToolRegistry.findTool("host_exec")).thenReturn(Optional.of(shellTool));

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "researcher",
                AutonomyLevel.SUPERVISED,
                "host_exec",
                Map.of("command", "ls"),
                List.of(),
                "goal",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
        assertTrue(((ToolInvocationGuard.GuardDecision.Blocked) decision)
                .observation()
                .contains("not permitted for role 'researcher'"));
    }

    @Test
    void activeLeaseSatisfiesDeterministicHitlPauseAndFallsThroughToClassifier() {
        // ADR-approval-lease-for-parallel-batches: the lease substitutes only for the
        // human prompt; the post-policy classifier below still runs.
        var shellTool = mock(AgentTool.class);
        when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(mockToolRegistry.findTool("host_exec")).thenReturn(Optional.of(shellTool));
        Map<String, Object> params = Map.of("command", "ls");
        when(guard.approvalLeaseStore.hasActiveLease("s1", "coder", null, "host_exec", params))
                .thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coder", AutonomyLevel.SUPERVISED, "host_exec", params, List.of(), "goal", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    @Test
    void withoutLeaseAlwaysHitlOperationStillPauses() {
        var shellTool = mock(AgentTool.class);
        when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(mockToolRegistry.findTool("host_exec")).thenReturn(Optional.of(shellTool));

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coder",
                AutonomyLevel.SUPERVISED,
                "host_exec",
                Map.of("command", "ls"),
                List.of(),
                "goal",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    @Test
    void blockedCapabilityBeatsActiveLease() {
        // Bound 2: every Blocked return precedes lease consultation.
        when(capabilityRegistry.hasCapability("researcher", "host_exec")).thenReturn(false);
        var shellTool = mock(AgentTool.class);
        when(shellTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(shellTool.requiredOperation()).thenReturn(ToolOperation.EXECUTE_SHELL);
        when(mockToolRegistry.findTool("host_exec")).thenReturn(Optional.of(shellTool));
        Map<String, Object> params = Map.of("command", "ls");
        when(guard.approvalLeaseStore.hasActiveLease("s1", "researcher", null, "host_exec", params))
                .thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "researcher", AutonomyLevel.SUPERVISED, "host_exec", params, List.of(), "g", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }

    @Test
    void scheduleTaskUnderSupervisedPausesBeforeExecutionForApprovalRequiringRoles() {
        // ADR-schedule-task-hitl-under-supervised: cron creation is operator-consented.
        var scheduleTool = mock(AgentTool.class);
        when(scheduleTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(scheduleTool.requiredOperation()).thenReturn(ToolOperation.SCHEDULE_AUTONOMOUS);
        when(mockToolRegistry.findTool("schedule_task")).thenReturn(Optional.of(scheduleTool));
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "schedule_task", Map.of(), List.of(), "g", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    @Test
    void manageScheduleStaysExemptUnderSupervised() {
        var manageTool = mock(AgentTool.class);
        when(manageTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(manageTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockToolRegistry.findTool("manage_schedule")).thenReturn(Optional.of(manageTool));
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "manage_schedule", Map.of(), List.of(), "g", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    @Test
    void fullAutonomyStillExecutesScheduleTaskWithoutPause() {
        // Deliberately unchanged: FULL executes scheduling without approval.
        var scheduleTool = mock(AgentTool.class);
        when(scheduleTool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(scheduleTool.requiredOperation()).thenReturn(ToolOperation.SCHEDULE_AUTONOMOUS);
        when(mockToolRegistry.findTool("schedule_task")).thenReturn(Optional.of(scheduleTool));
        when(roleRegistry.requiresApprovalForTools("coordinator")).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coordinator", AutonomyLevel.FULL, "schedule_task", Map.of(), List.of(), "g", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }
}
