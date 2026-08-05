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

import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.security.ToolOperation;
import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.IdempotencyMode;
import com.amsha.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolInvocationGuardMemoryReadTest {

    private ToolInvocationGuard guard;
    private RoleRegistry roleRegistry;
    private ToolRegistry toolRegistry;

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
        guard.dynamicToolAdmissionLookup = dynamicToolAdmissionLookup;
        guard.init();
    }

    @Test
    void supervisedCoreMemoryReadDoesNotPauseForApproval() {
        AgentTool tool = mockTool(ToolOperation.WRITE_MEMORY);
        when(toolRegistry.findTool(ToolCapability.CORE_MEMORY_READ.toolName())).thenReturn(Optional.of(tool));
        when(roleRegistry.requiresApprovalForTools(anyString())).thenReturn(true);

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                ToolCapability.CORE_MEMORY_READ.toolName(),
                Map.<String, Object>of("block_key", "user_profile"),
                List.of(),
                "do memory search for my nickname",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    private static AgentTool mockTool(ToolOperation operation) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.idempotencyMode()).thenReturn(IdempotencyMode.NON_IDEMPOTENT);
        when(tool.requiresManualApproval(any())).thenReturn(false);
        when(tool.requiredOperation()).thenReturn(operation);
        return tool;
    }
}
