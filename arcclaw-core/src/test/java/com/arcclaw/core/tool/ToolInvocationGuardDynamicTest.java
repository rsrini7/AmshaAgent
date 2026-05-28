package com.arcclaw.core.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.arcclaw.core.agent.loop.ToolInvocationGuard;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolInvocationGuardDynamicTest {

    private ToolInvocationGuard guard;
    private DynamicToolLookup mockLookup;
    private Instance<DynamicToolLookup> mockInstance;
    private ToolRegistry mockToolRegistry;
    private Instance<ToolGuardInterceptor> mockInterceptors;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        guard = new ToolInvocationGuard();
        mockLookup = mock(DynamicToolLookup.class);
        this.mockInstance = mock(Instance.class);
        this.mockInterceptors = mock(Instance.class);
        when(mockInterceptors.iterator()).thenReturn(Collections.emptyIterator());
        when(mockInterceptors.stream()).thenReturn(Stream.empty());

        mockToolRegistry = mock(ToolRegistry.class);

        // Manual injection for unit test
        guard.dynamicToolLookup = mockInstance;
        guard.roleRegistry = mock(RoleRegistry.class);
        guard.objectMapper = new ObjectMapper();
        guard.toolRegistry = mockToolRegistry;
        guard.interceptors = mockInterceptors;

        guard.init(); // Initialize sortedInterceptors
    }

    @Test
    void testUnsatisfiedInstanceDoesNotBreakGuard() {
        when(mockInstance.isUnsatisfied()).thenReturn(true);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        // Root Cause 1: When dynamic lookup is unsatisfied, isDynamicAllowed=false -> Blocked
        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                ToolCapability.GITHUB.toolName(),
                Map.of("op", "search"),
                Collections.emptyList(),
                "just a question",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }

    @Test
    void testPermitRegisteredDynamicTool() {
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(mockLookup);
        when(mockLookup.isDynamicToolAllowed("external_tool")).thenReturn(true);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "external_tool",
                Map.of("arg", "val"),
                Collections.emptyList(),
                "execute external search",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    @Test
    void testBlockDynamicToolWhenDynamicAllowlistRejects() {
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(mockLookup);
        when(mockLookup.isDynamicToolAllowed("some_tool")).thenReturn(false);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        // Root Cause 1: Rejected dynamic tool -> Blocked
        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1",
                "coordinator",
                AutonomyLevel.SUPERVISED,
                "some_tool",
                Map.of(),
                Collections.emptyList(),
                "just a question",
                List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
    }
}
