package com.amsha.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import com.amsha.app.tool.ToolAttemptBoundaryProbe;
import com.amsha.core.agent.loop.ToolInvocationGuard;

import org.junit.jupiter.api.Test;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Proves a mandatory guard failure surfaces as a blocked MCP error and never reaches the executor. */
@QuarkusTest
@TestProfile(McpToolAttemptGuardFailureTest.Profile.class)
class McpToolAttemptGuardFailureTest {

    @Inject
    McpToolBridge bridge;

    @Inject
    ToolAttemptBoundaryProbe probe;

    @InjectMock
    ToolInvocationGuard guard;

    @Test
    void guardExceptionReturnsBlockedErrorAndSkipsExecutor() {
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), any(), anyList()))
                .thenThrow(new IllegalStateException("durable store unavailable"));

        String result = bridge.executeTool(
                McpToolAttemptExecutionBoundaryTest.mcpRequest("guard-failure-client", "guard-failure-session"));

        assertEquals("Error: Tool invocation was blocked by mandatory safety checks.", result);
        assertEquals(0, probe.invocationCount());
        verify(guard).evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), any(), anyList());
    }

    public static class Profile extends McpToolAttemptExecutionBoundaryTest.Profile {}
}
