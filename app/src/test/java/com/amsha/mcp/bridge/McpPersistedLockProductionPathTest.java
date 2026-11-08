package com.amsha.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amsha.app.tool.ToolAttemptBoundaryProbe;
import com.amsha.core.agent.loop.ToolInvocationGuard;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tool.attempt.ActionFingerprintGenerator;
import com.amsha.core.tool.attempt.ToolAttemptLockPolicy;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
import com.amsha.core.tool.attempt.ToolTargetResolution;
import com.amsha.core.tool.attempt.ToolTargetResolver;
import com.amsha.persistence.api.DurableActionLockDto;
import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptAdmissionResult;
import com.amsha.persistence.api.ToolAttemptDto;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.persistence.api.ToolAttemptSettlementFields;
import com.amsha.persistence.api.ToolAttemptStatus;
import com.amsha.persistence.api.ToolAttemptStorePort;
import com.amsha.persistence.api.ToolAttemptTransitionResult;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Verifies that a persisted SQLite lock blocks direct guard evaluation and the MCP bridge.
 * The separate {@code ToolAttemptPersistedLockHandlerIntegrationTest} covers the production
 * handler path to the guard's blocking branch with the actual ToolInvoker wired.
 */
@QuarkusTest
@TestProfile(McpPersistedLockProductionPathTest.Profile.class)
class McpPersistedLockProductionPathTest {

    private static final String CLIENT_ID = "persisted-lock-client";
    private static final String MCP_SESSION = "persisted-lock-session";

    @Inject
    McpToolBridge bridge;

    @Inject
    ToolAttemptBoundaryProbe probe;

    @Inject
    ToolAttemptStorePort attemptStore;

    @Inject
    ToolInvocationGuard guard;

    @Inject
    ActionFingerprintGenerator fingerprints;

    @Inject
    ToolTargetResolver targetResolver;

    @Test
    void persistedIndeterminateLockBlocksGuardInvokerPathAndMcpBridge() throws Exception {
        String sessionId = "mcp-" + CLIENT_ID + "-" + MCP_SESSION;
        String workspaceId = "mcp-client:" + CLIENT_ID;
        String tool = ToolAttemptBoundaryProbe.TOOL_NAME;
        Map<String, Object> params = Map.of();

        String actionFingerprint = fingerprints.generate(workspaceId, sessionId, tool, params);
        ToolTargetResolution target = targetResolver.resolve(workspaceId, sessionId, tool, params);

        Instant now = Instant.now();
        ToolAttemptAdmissionRequest request = new ToolAttemptAdmissionRequest(
                workspaceId,
                sessionId,
                UUID.randomUUID().toString(),
                null,
                tool,
                actionFingerprint,
                ToolOperationIdentity.CURRENT_FINGERPRINT_VERSION,
                target.targetFingerprint(),
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT,
                "BUILT_IN",
                "{}",
                now);
        ToolAttemptAdmissionResult admitted = attemptStore.admit(request);
        assertEquals(ToolAttemptAdmissionResult.AdmissionStatus.CREATED, admitted.status());
        ToolAttemptDto attempt = admitted.attempt();

        attempt = attemptStore
                .transition(
                        workspaceId,
                        attempt.attemptId(),
                        ToolAttemptStatus.ADMITTED,
                        attempt.fencingToken(),
                        ToolAttemptStatus.LAUNCH_REQUESTED,
                        ToolAttemptSettlementFields.forLaunch(now),
                        null,
                        List.of())
                .updatedAttempt();
        attempt = attemptStore
                .transition(
                        workspaceId,
                        attempt.attemptId(),
                        ToolAttemptStatus.LAUNCH_REQUESTED,
                        attempt.fencingToken(),
                        ToolAttemptStatus.RUNNING,
                        ToolAttemptSettlementFields.forRunning(now, "persisted-handle", "persisted-pid"),
                        null,
                        List.of())
                .updatedAttempt();
        DurableActionLockDto lock = ToolAttemptLockPolicy.createLock(attempt, "Persisted test lock.", now);
        ToolAttemptTransitionResult settled = attemptStore.transition(
                workspaceId,
                attempt.attemptId(),
                ToolAttemptStatus.RUNNING,
                attempt.fencingToken(),
                ToolAttemptStatus.INDETERMINATE,
                new ToolAttemptSettlementFields(now, "persisted-handle", "persisted-pid", null, "test indeterminate"),
                lock,
                List.of());
        assertEquals(ToolAttemptTransitionResult.TransitionStatus.APPLIED, settled.status());

        assertFalse(attemptStore
                .findApplicableLocks(workspaceId, sessionId, actionFingerprint, target.targetFingerprint())
                .isEmpty());

        ToolInvocationGuard.GuardDecision decision = WorkspaceContext.callWithActiveWorkspace(
                workspaceId,
                () -> guard.evaluate(
                        sessionId, "coder", AutonomyLevel.FULL, tool, params, List.of(), "goal", List.of()));
        assertInstanceOf(ToolInvocationGuard.GuardDecision.Blocked.class, decision);
        assertEquals(0, probe.invocationCount());

        String result = bridge.executeTool(McpToolAttemptExecutionBoundaryTest.mcpRequest(CLIENT_ID, MCP_SESSION));
        assertEquals("Error: Tool invocation was blocked by mandatory safety checks.", result);
        assertEquals(0, probe.invocationCount());
    }

    public static class Profile extends McpToolAttemptExecutionBoundaryTest.Profile {}
}
