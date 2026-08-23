package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

/**
 * F9 regression: an approved HITL resume must run the loop at the CHECKPOINT's own
 * autonomy level — never blanket FULL. A single approval elevates only the one approved
 * tool call (the tightly-scoped {@code runWithFullAutonomy} inside
 * PendingToolResumeExecutor / ParallelBatchResumeHandler), not the rest of the session.
 */
class CheckpointResumeHandlerAutonomyTest {

    private static AgentCheckpoint checkpointWith(AutonomyLevel level) {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getAutonomyLevel()).thenReturn(level);
        return cp;
    }

    @Test
    void resumeAutonomy_UsesCheckpointLevel() {
        assertEquals(
                AutonomyLevel.SUPERVISED,
                CheckpointResumeHandler.resumeAutonomy(checkpointWith(AutonomyLevel.SUPERVISED)));
        assertEquals(AutonomyLevel.FULL, CheckpointResumeHandler.resumeAutonomy(checkpointWith(AutonomyLevel.FULL)));
        assertEquals(
                AutonomyLevel.READONLY, CheckpointResumeHandler.resumeAutonomy(checkpointWith(AutonomyLevel.READONLY)));
    }

    @Test
    void resumeAutonomy_NullCheckpointLevel_FallsBackToSupervised() {
        assertEquals(AutonomyLevel.SUPERVISED, CheckpointResumeHandler.resumeAutonomy(checkpointWith(null)));
    }

    @Test
    void resumedLoopBinding_CarriesCheckpointAutonomyNotFull() throws Exception {
        // Prove the value that actually binds the resumed loop's WorkspaceContext is the
        // checkpoint's level — this is what ToolInvocationGuard sees for post-resume calls.
        AgentCheckpoint cp = checkpointWith(AutonomyLevel.SUPERVISED);
        AutonomyLevel observed = WorkspaceContext.run(
                "sess-f9", "user-1", "chat-1", CheckpointResumeHandler.resumeAutonomy(cp), "coordinator", () -> {
                    assertEquals(AutonomyLevel.SUPERVISED, WorkspaceContext.requireAutonomy());
                    return WorkspaceContext.requireAutonomy();
                });
        assertEquals(AutonomyLevel.SUPERVISED, observed);
    }
}
