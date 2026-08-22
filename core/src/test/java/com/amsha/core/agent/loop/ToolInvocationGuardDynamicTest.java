package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.amsha.core.tool.DynamicToolAdmission;
import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.ToolCategory;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.security.api.ToolOperation;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolInvocationGuardDynamicTest {

    private ToolInvocationGuard guard;
    private DynamicToolAdmissionLookup mockAdmissionLookup;
    private Instance<DynamicToolAdmissionLookup> mockAdmissionInstance;
    private ToolRegistry mockToolRegistry;
    private Instance<ToolGuardInterceptor> mockInterceptors;
    private RoleRegistry roleRegistry;
    private PostPolicyToolRiskDispatcher postPolicyToolRiskDispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new ToolInvocationGuard();
        mockAdmissionLookup = mock(DynamicToolAdmissionLookup.class);
        mockAdmissionInstance = mock(Instance.class);
        mockInterceptors = mock(Instance.class);
        roleRegistry = mock(RoleRegistry.class);
        postPolicyToolRiskDispatcher = mock(PostPolicyToolRiskDispatcher.class);
        when(mockInterceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(mockInterceptors.stream()).thenReturn(Stream.empty());
        when(postPolicyToolRiskDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.NoOpinion<>(NoOpinionReason.MISSING_MODEL));

        mockToolRegistry = mock(ToolRegistry.class);

        guard.dynamicToolAdmissionLookup = mockAdmissionInstance;
        guard.roleRegistry = roleRegistry;
        guard.objectMapper = new ObjectMapper();
        guard.toolRegistry = mockToolRegistry;
        guard.interceptors = mockInterceptors;
        guard.postPolicyToolRiskDispatcher = postPolicyToolRiskDispatcher;

        guard.init();
    }

    @Test
    void unsatisfiedAdmissionLookupBlocksDynamicTools() {
        when(mockAdmissionInstance.isUnsatisfied()).thenReturn(true);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision decision = evaluate("external_tool", AutonomyLevel.FULL, false);

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }

    @Test
    void ambiguousAdmissionLookupBlocksDynamicTools() {
        when(mockAdmissionInstance.isUnsatisfied()).thenReturn(false);
        when(mockAdmissionInstance.isAmbiguous()).thenReturn(true);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision decision = evaluate("external_tool", AutonomyLevel.FULL, false);

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }

    @Test
    void throwingAdmissionLookupBlocksDynamicTools() {
        when(mockAdmissionInstance.isUnsatisfied()).thenReturn(false);
        when(mockAdmissionInstance.isAmbiguous()).thenReturn(false);
        when(mockAdmissionInstance.get()).thenThrow(new IllegalStateException("cdi failure"));
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision decision = evaluate("external_tool", AutonomyLevel.FULL, false);

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }

    @Test
    void missingAdmissionBlocksDynamicTools() {
        stubAdmission(Optional.empty());
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision decision = evaluate("external_tool", AutonomyLevel.SUPERVISED, false);

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }

    @Test
    void networkDynamicToolRequiresHitlAtFullTrustedRole() {
        stubAdmission(Optional.of(networkAdmission(false)));
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(false);

        ToolInvocationGuard.GuardDecision decision = evaluate("external_search", AutonomyLevel.FULL, false);

        var pause = assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
        assertEquals(RiskLevel.MEDIUM, pause.frame().riskLevel());
    }

    @Test
    void networkDynamicToolRequiresHitlAtFullUntrustedRole() {
        stubAdmission(Optional.of(networkAdmission(false)));
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = evaluate("external_search", AutonomyLevel.FULL, true);

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    @Test
    void requiresApprovalForcesHitlAtFullForNonSensitiveOperation() {
        stubAdmission(Optional.of(
                new DynamicToolAdmission("memory_tool", ToolCategory.DATABASE, true, ToolOperation.WRITE_MEMORY)));
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(false);

        ToolInvocationGuard.GuardDecision decision = evaluate("memory_tool", AutonomyLevel.FULL, false);

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    @Test
    void postPolicyRequireApprovalUsesAdmissionRiskLevel() {
        stubAdmission(Optional.of(networkAdmission(false)));
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(false);
        // Non-sensitive path: use WRITE_MEMORY admission temporarily would hit HITL via requiresApproval;
        // use DATABASE write with requiresApproval false — but NETWORK always HITL.
        // For provisional allow we need non-sensitive op without requiresApproval/role HITL at FULL.
        stubAdmission(Optional.of(
                new DynamicToolAdmission("db_tool", ToolCategory.DATABASE, false, ToolOperation.WRITE_MEMORY)));
        when(postPolicyToolRiskDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.Opinion<>(ToolRiskAction.REQUIRE_APPROVAL, 0.9f, "tool-risk-v1"));

        ToolInvocationGuard.GuardDecision decision = evaluate("db_tool", AutonomyLevel.FULL, false);

        var pause = assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
        assertEquals(ToolOperation.WRITE_MEMORY.riskLevel(), pause.frame().riskLevel());
    }

    private void stubAdmission(Optional<DynamicToolAdmission> admission) {
        when(mockAdmissionInstance.isUnsatisfied()).thenReturn(false);
        when(mockAdmissionInstance.isAmbiguous()).thenReturn(false);
        when(mockAdmissionInstance.get()).thenReturn(mockAdmissionLookup);
        when(mockAdmissionLookup.findAdmission(anyString())).thenReturn(admission);
    }

    private static DynamicToolAdmission networkAdmission(boolean requiresApproval) {
        return new DynamicToolAdmission(
                "external_search", ToolCategory.NETWORK, requiresApproval, ToolOperation.NETWORK_OUTBOUND);
    }

    private ToolInvocationGuard.GuardDecision evaluate(String toolName, AutonomyLevel level, boolean untrusted) {
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(untrusted);
        return guard.evaluate(
                "s1",
                "coder",
                level,
                toolName,
                Map.of("arg", "val"),
                Collections.emptyList(),
                "execute external search",
                List.of());
    }
}
