package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;

import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.ToolOperation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class NullParamStrippingTest {

    @Inject
    ToolRegistry registry;

    @InjectMock
    ToolDiscoveryService discoveryService;

    @InjectMock
    ToolGovernanceGuard governanceGuard;

    private AgentTool mockTool;

    @BeforeEach
    void setUp() {
        mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("test-tool");
        when(mockTool.isEnabled()).thenReturn(true);
        when(mockTool.requiredOperation()).thenReturn(ToolOperation.READ_FS);
        when(mockTool.requiresSandbox()).thenReturn(false);
        when(mockTool.execute(any(), any())).thenReturn(ToolResponse.success("ok"));

        when(discoveryService.getToolIndex()).thenReturn(Map.of("test-tool", mockTool));
    }

    @Test
    void testDefaultBehavior_StripsNulls() {
        when(mockTool.shouldStripNullParams()).thenReturn(true);

        Map<String, Object> inputParams = new HashMap<>();
        inputParams.put("keep", "value");
        inputParams.put("strip", null);

        WorkspaceContext.run("s1", "user", "chat", AutonomyLevel.FULL, "coordinator", () -> {
            registry.invokeTool("test-tool", inputParams);

            verify(mockTool).execute(any(), argThat(params -> {
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

            verify(mockTool).execute(any(), argThat(params -> {
                assertTrue(params.containsKey("keep"));
                assertTrue(params.containsKey("preserve"));
                assertTrue(params.get("preserve") == null);
                return true;
            }));
        });
    }
}
