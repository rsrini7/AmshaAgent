package com.amsha.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import java.util.Set;

import com.amsha.app.tool.FailingAttemptAdmissionAuditStore;
import com.amsha.app.tool.ToolAttemptBoundaryProbe;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Proves an unacknowledged admission event prevents MCP executor invocation. */
@QuarkusTest
@TestProfile(McpToolAttemptAuditGateTest.Profile.class)
class McpToolAttemptAuditGateTest {
    @Inject
    McpToolBridge bridge;

    @Inject
    ToolAttemptBoundaryProbe probe;

    @Test
    void doesNotInvokeExecutorWhenAdmissionAuditAcknowledgementFails() {
        String result = bridge.executeTool(McpToolAttemptExecutionBoundaryTest.mcpRequest(
                "attempt-audit-failure-client", "audit-failure-session"));

        assertEquals("Error: Tool execution is blocked by durable safety checks.", result);
        assertEquals(0, probe.invocationCount());
    }

    public static class Profile extends McpToolAttemptExecutionBoundaryTest.Profile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ToolAttemptBoundaryProbe.class, FailingAttemptAdmissionAuditStore.class);
        }
    }
}
