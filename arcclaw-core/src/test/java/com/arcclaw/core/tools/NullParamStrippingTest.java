package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.role.CapabilityRegistry;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.ToolOperation;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NullParamStrippingTest {

    private ToolRegistry registry;
    private AgentTool mockTool;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.auditService = mock(AuditService.class);
        registry.capabilityRegistry = mock(CapabilityRegistry.class);
        registry.roleRegistry = mock(RoleRegistry.class);
        when(registry.roleRegistry.permittedTools(any())).thenReturn(Set.of("test-tool"));
        
        mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("test-tool");
        when(mockTool.isEnabled()).thenReturn(true);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.execute(any())).thenReturn(ToolResponse.success("ok"));
        
        // Mock findTool by mocking discoveredTools Instance
        @SuppressWarnings("unchecked")
        Instance<AgentTool> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(mockTool).iterator());
        when(instance.spliterator()).thenReturn(List.of(mockTool).spliterator());
        registry.discoveredTools = instance;
    }

    @Test
    void testDefaultBehavior_StripsNulls() {
        when(mockTool.shouldStripNullParams()).thenReturn(true);
        
        Map<String, Object> inputParams = new HashMap<>();
        inputParams.put("keep", "value");
        inputParams.put("strip", null);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            registry.invokeTool("test-tool", inputParams);
            
            verify(mockTool).execute(argThat(params -> {
                assertTrue(params.containsKey("keep"));
                assertFalse(params.containsKey("strip"));
                return true;
            }));
        });
    }

    @Test
    void testOptOutBehavior_PreservesNulls() {
        when(mockTool.shouldStripNullParams()).thenReturn(false);
        
        Map<String, Object> inputParams = new HashMap<>();
        inputParams.put("keep", "value");
        inputParams.put("preserve", null);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            registry.invokeTool("test-tool", inputParams);
            
            verify(mockTool).execute(argThat(params -> {
                assertTrue(params.containsKey("keep"));
                assertTrue(params.containsKey("preserve"));
                assertTrue(params.get("preserve") == null);
                return true;
            }));
        });
    }
}
