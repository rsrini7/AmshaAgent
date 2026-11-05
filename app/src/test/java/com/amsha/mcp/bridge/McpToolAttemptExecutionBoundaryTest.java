package com.amsha.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import com.amsha.app.tool.ToolAttemptBoundaryProbe;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;

/** Exercises MCP admission, audit acknowledgement, and execution with real SQLite wiring. */
@QuarkusTest
@TestProfile(McpToolAttemptExecutionBoundaryTest.Profile.class)
class McpToolAttemptExecutionBoundaryTest {
    private static final String ACKNOWLEDGED_BEFORE_EXECUTION = "pre-launch-gates-acknowledged";

    @Inject
    McpToolBridge bridge;

    @Test
    void persistsAdmissionAndLaunchAuditBeforeCallingExecutor() {
        String clientId = "attempt-boundary-client";
        String sessionId = "attempt-boundary-session";
        String result = bridge.executeTool(mcpRequest(clientId, sessionId));

        assertEquals(ACKNOWLEDGED_BEFORE_EXECUTION, result);
    }

    static McpToolRequest mcpRequest(String clientId, String sessionId) {
        HttpServerRequest request = HttpServerRequest.class.cast(Proxy.newProxyInstance(
                McpToolAttemptExecutionBoundaryTest.class.getClassLoader(),
                new Class[] {HttpServerRequest.class},
                (proxy, method, args) -> {
                    if ("headers".equals(method.getName())) {
                        return MultiMap.caseInsensitiveMultiMap()
                                .add("X-MCP-Client-ID", clientId)
                                .add("X-MCP-Session-ID", sessionId)
                                .add("X-MCP-Capabilities", "code_generation");
                    }
                    return null;
                }));
        return new McpToolRequest(ToolAttemptBoundaryProbe.TOOL_NAME, "{}", request);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("amsha.mcp.exposed-tools", ToolAttemptBoundaryProbe.TOOL_NAME);
        }

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ToolAttemptBoundaryProbe.class);
        }
    }
}
