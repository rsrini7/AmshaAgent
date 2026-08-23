package com.amsha.core.agent.loop;

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

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class ToolInvocationGuardApprovalParityTest {

    ToolInvocationGuard guard;
    RoleRegistry roleRegistry;
    ToolRegistry toolRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new ToolInvocationGuard();
        roleRegistry = mock(RoleRegistry.class);
        toolRegistry = mock(ToolRegistry.class);
        Instance<ToolGuardInterceptor> interceptors = mock(Instance.class);
        Instance<DynamicToolAdmissionLookup> dynamicToolAdmissionLookup = mock(Instance.class);

        when(interceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(interceptors.stream()).thenReturn(Stream.empty());
        when(dynamicToolAdmissionLookup.isUnsatisfied()).thenReturn(true);

        guard.roleRegistry = roleRegistry;
        guard.objectMapper = new ObjectMapper();
        guard.toolRegistry = toolRegistry;
        guard.interceptors = interceptors;
        guard.capabilityRegistry = Mockito.mock(CapabilityRegistry.class);
        Mockito.when(guard.capabilityRegistry.hasCapability(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(true);
        guard.autonomyPolicy = new com.amsha.core.security.AutonomyPolicy.Standard();
        guard.approvalLeaseStore = org.mockito.Mockito.mock(ApprovalLeaseStore.class);
        org.mockito.Mockito.when(guard.approvalLeaseStore.hasActiveLease(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        guard.dynamicToolAdmissionLookup = dynamicToolAdmissionLookup;
        guard.init();
    }

    @Test
    void supervisedIngestDocumentPausesBeforeInvocation() {
        AgentTool tool = mockTool(ToolOperation.WRITE_MEMORY);
        when(toolRegistry.findTool(ToolCapability.INGEST_DOCUMENT.toolName())).thenReturn(Optional.of(tool));
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                ToolCapability.INGEST_DOCUMENT.toolName(),
                Map.of("text", "user_name=Srini"),
                List.of(),
                "remember this",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    @Test
    void supervisedDelegationPausesBeforeInvocation() {
        AgentTool tool = mockTool(ToolOperation.DELEGATE_AUTONOMOUS);
        when(toolRegistry.findTool(ToolCapability.DELEGATE_TO_AGENT.toolName())).thenReturn(Optional.of(tool));
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                ToolCapability.DELEGATE_TO_AGENT.toolName(),
                Map.of("agent", "saraswati", "goal", "search"),
                List.of(),
                "search",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.PauseForApproval.class, decision);
    }

    private AgentTool mockTool(ToolOperation operation) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(tool.requiresManualApproval(any())).thenReturn(false);
        when(tool.requiredOperation()).thenReturn(operation);
        return tool;
    }
}
