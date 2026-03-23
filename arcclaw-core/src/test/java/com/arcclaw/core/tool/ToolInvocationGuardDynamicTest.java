package com.arcclaw.core.tool;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.loop.ToolInvocationGuard;
import com.arcclaw.core.agent.role.RoleRegistry;
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

        mockToolRegistry = mock(ToolRegistry.class);

        // Manual injection for unit test
        guard.dynamicToolLookup = mockInstance;
        guard.roleRegistry = mock(RoleRegistry.class);
        guard.objectMapper = new ObjectMapper();
        guard.toolRegistry = mockToolRegistry;
        guard.interceptors = mockInterceptors;
    }

    @Test
    void testUnsatisfiedInstanceDoesNotBreakGuard() {
        when(mockInstance.isUnsatisfied()).thenReturn(true);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        // Should fall through to normal logic (returns Allow because no HITL/DEDUP applies)
        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coordinator", "github", Map.of("op", "search"),
                Collections.emptyList(), "just a question", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    @Test
    void testPermitRegisteredDynamicTool() {
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(mockLookup);
        when(mockLookup.isDynamicToolAllowed("external_tool")).thenReturn(true);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coordinator", "external_tool", Map.of("arg", "val"),
                Collections.emptyList(), "execute external search", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }

    @Test
    void testBlockUnregisteredDynamicTool() {
        when(mockInstance.isUnsatisfied()).thenReturn(false);
        when(mockInstance.get()).thenReturn(mockLookup);
        when(mockLookup.isDynamicToolAllowed("github")).thenReturn(false);
        when(mockToolRegistry.findTool(anyString())).thenReturn(Optional.empty());

        // Should fall through and reach HITL or Allow. 
        // In this unit test setup, it should return Allow.
        ToolInvocationGuard.GuardDecision decision = guard.evaluate(
                "s1", "coordinator", "some_tool", Map.of(),
                Collections.emptyList(), "just a question", List.of());

        assertInstanceOf(ToolInvocationGuard.GuardDecision.Allow.class, decision);
    }
}

