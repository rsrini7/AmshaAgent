package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.hitl.ApprovalLeaseStore;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyPolicy;
import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tool.attempt.DurableToolAttemptGuard;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.IdempotencyMode;

import org.junit.jupiter.api.Test;

class ToolInvocationGuardDurableFenceTest {
    @SuppressWarnings("unchecked")
    @Test
    void durableLockBlocksBeforeCachedHistoryResultCanDeduplicate() {
        ToolInvocationGuard guard = new ToolInvocationGuard();
        RoleRegistry roleRegistry = mock(RoleRegistry.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ApprovalLeaseStore leaseStore = mock(ApprovalLeaseStore.class);
        Instance<ToolGuardInterceptor> interceptors = mock(Instance.class);
        Instance<DynamicToolAdmissionLookup> dynamicLookups = mock(Instance.class);
        DurableToolAttemptGuard durableGuard = mock(DurableToolAttemptGuard.class);
        AgentTool tool = mock(AgentTool.class);
        Map<String, Object> parameters = Map.of("arg", 1);

        when(capabilityRegistry.hasCapability(anyString(), anyString())).thenReturn(true);
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(false);
        when(leaseStore.hasActiveLease(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(false);
        when(interceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(interceptors.stream()).thenReturn(Stream.empty());
        when(dynamicLookups.isUnsatisfied()).thenReturn(true);
        when(toolRegistry.findTool("tool")).thenReturn(Optional.of(tool));
        when(tool.idempotencyMode()).thenReturn(IdempotencyMode.IDEMPOTENT);
        when(tool.requiredOperation()).thenReturn(ToolOperation.WRITE_MEMORY);
        when(tool.validateParameters(parameters)).thenReturn(null);
        when(durableGuard.check("workspace-1", "session-1", "tool", parameters, IdempotencyMode.IDEMPOTENT))
                .thenReturn(Optional.of(DurableToolAttemptGuard.Decision.blocked("unresolved attempt")));

        guard.roleRegistry = roleRegistry;
        guard.capabilityRegistry = capabilityRegistry;
        guard.toolRegistry = toolRegistry;
        guard.approvalLeaseStore = leaseStore;
        guard.autonomyPolicy = new AutonomyPolicy.Standard();
        guard.interceptors = interceptors;
        guard.dynamicToolAdmissionLookup = dynamicLookups;
        guard.durableToolAttemptGuard = durableGuard;
        guard.init();
        guard.recordToolCall("session-1", "tool", parameters, "cached result");

        WorkspaceContext.runWithActiveWorkspace("workspace-1", () -> {
            ToolInvocationGuard.GuardDecision result = guard.evaluate(
                    "session-1", "coordinator", AutonomyLevel.FULL, "tool", parameters, List.of(), "goal", List.of());

            assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, result);
        });

        verify(durableGuard).check("workspace-1", "session-1", "tool", parameters, IdempotencyMode.IDEMPOTENT);
    }
}
