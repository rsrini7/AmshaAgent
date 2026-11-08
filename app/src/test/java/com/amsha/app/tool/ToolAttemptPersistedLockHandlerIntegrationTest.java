package com.amsha.app.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.StepDecisionRouter;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.tool.attempt.ActionFingerprintGenerator;
import com.amsha.core.tool.attempt.ToolAttemptLockPolicy;
import com.amsha.core.tool.attempt.ToolOperationIdentity;
import com.amsha.core.tool.attempt.ToolTargetResolver;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.llm.api.StructuredOutputMode;
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
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Exercises the real step router and ToolStepHandler through the durable guard's blocking
 * branch, with the actual ToolInvoker wired and the selected probe tool registered.
 */
@QuarkusTest
@TestProfile(ToolAttemptPersistedLockHandlerIntegrationTest.Profile.class)
class ToolAttemptPersistedLockHandlerIntegrationTest {

    private static final String TOOL = ToolAttemptBoundaryProbe.TOOL_NAME;

    @Inject
    StepDecisionRouter stepRouter;

    @Inject
    ToolAttemptStorePort attemptStore;

    @Inject
    ToolAttemptBoundaryProbe probe;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    ActionFingerprintGenerator fingerprints;

    @Inject
    ToolTargetResolver targetResolver;

    @Test
    void persistedIndeterminateLockBlocksToolInvokerBeforeSelectedExecutor() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workspaceId = "tool-invoker-lock-" + suffix;
        String sessionId = "tool-invoker-session-" + suffix;
        Map<String, Object> params = Map.of();
        String actionFingerprint = fingerprints.generate(workspaceId, sessionId, TOOL, params);
        String targetFingerprint =
                targetResolver.resolve(workspaceId, sessionId, TOOL, params).targetFingerprint();

        persistIndeterminateLock(workspaceId, sessionId, actionFingerprint, targetFingerprint);
        assertFalse(attemptStore
                .findApplicableLocks(workspaceId, sessionId, actionFingerprint, targetFingerprint)
                .isEmpty());
        assertTrue(toolRegistry.findTool(TOOL).isPresent(), "the selected probe tool must be registered");

        int invocationCountBefore = probe.invocationCount();
        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome = WorkspaceContext.callWithActiveWorkspace(
                workspaceId,
                () -> stepRouter.route(
                        context(sessionId),
                        history,
                        ReasoningStep.pending("persisted lock", TOOL, params),
                        0,
                        0,
                        List.of()));

        assertFalse(outcome.isTerminal());
        assertEquals(1, history.size());
        assertFalse(history.getFirst().success());
        assertEquals(
                "Tool action is blocked by an unresolved prior execution.",
                history.getFirst().observation());
        assertEquals(invocationCountBefore, probe.invocationCount());
    }

    private void persistIndeterminateLock(
            String workspaceId, String sessionId, String actionFingerprint, String targetFingerprint) {
        Instant now = Instant.now();
        ToolAttemptAdmissionRequest request = new ToolAttemptAdmissionRequest(
                workspaceId,
                sessionId,
                UUID.randomUUID().toString(),
                null,
                TOOL,
                actionFingerprint,
                ToolOperationIdentity.CURRENT_FINGERPRINT_VERSION,
                targetFingerprint,
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
    }

    private static AgentExecutionContext context(String sessionId) {
        return new AgentExecutionContext(
                sessionId,
                "tool-invoker-user",
                "tool-invoker-chat",
                AgentRoleEnum.CODER_ID,
                "exercise persisted lock",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                "MCP",
                "tool-invoker-client",
                null,
                Map.of());
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(ToolAttemptBoundaryProbe.class);
        }
    }
}
