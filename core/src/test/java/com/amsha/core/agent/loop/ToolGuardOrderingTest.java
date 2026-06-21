package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.skill.CompiledSkillEntry;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tool.InterceptorPriority;
import com.amsha.core.tool.ToolGuardInterceptor;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ToolGuardOrderingTest {

    @Inject
    ToolInvocationGuard guard;

    @Test
    void testInterceptorOrdering() {
        // Interceptor A: Priority 10, returns BLOCKED
        // Interceptor B: Priority 5, returns ALLOW (should win)

        List<String> executionOrder = new ArrayList<>();

        // We can't easily mock the Instance<ToolGuardInterceptor> in the real injected guard
        // but we can manually verify the sorting logic if we want, or use a manual registry.

        // Let's create a manual guard for testing the sorting logic.
        ToolInvocationGuard manualGuard = new ToolInvocationGuard();

        @SuppressWarnings("unchecked")
        Instance<ToolGuardInterceptor> mockInstance = mock(Instance.class);

        // Wrap our anonymous classes with priority via a proxy or just use classes with annotations.
        @InterceptorPriority(10)
        class InterceptorA extends MockInterceptor {
            public InterceptorA(List<String> order) {
                super("A", order);
            }
        }

        @InterceptorPriority(5)
        class InterceptorB extends MockInterceptor {
            public InterceptorB(List<String> order) {
                super("B", order);
            }
        }

        List<ToolGuardInterceptor> interceptors =
                List.of(new InterceptorA(executionOrder), new InterceptorB(executionOrder));
        when(mockInstance.stream()).thenReturn(interceptors.stream());

        manualGuard.interceptors = mockInstance;
        manualGuard.roleRegistry = mock(RoleRegistry.class);
        manualGuard.objectMapper = new ObjectMapper();
        manualGuard.toolRegistry = mock(ToolRegistry.class);
        when(manualGuard.toolRegistry.findTool(any())).thenReturn(Optional.of(mock(AgentTool.class)));

        @SuppressWarnings("unchecked")
        Instance<DynamicToolLookup> mockLookup = mock(Instance.class);
        when(mockLookup.isUnsatisfied()).thenReturn(true);
        manualGuard.dynamicToolLookup = mockLookup;

        manualGuard.init(); // Triggers sorting

        // Act
        manualGuard.evaluate(
                "s1", "coordinator", AutonomyLevel.SUPERVISED, "test-tool", Map.of(), List.of(), "goal", List.of());

        // Assert
        assertEquals(
                List.of("B", "A"), executionOrder, "Interceptor B (priority 5) should execute before A (priority 10)");
    }

    static class MockInterceptor implements ToolGuardInterceptor {
        String name;
        List<String> order;

        public MockInterceptor(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public Set<String> handledToolNames() {
            return Set.of();
        }

        @Override
        public Optional<ToolInvocationGuard.GuardDecision> evaluate(
                String s,
                String r,
                AutonomyLevel l,
                String t,
                Map<String, Object> p,
                List<ReasoningStep> h,
                String g,
                List<CompiledSkillEntry> sk) {
            order.add(name);
            return Optional.empty(); // Continue
        }
    }
}
