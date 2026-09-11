package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@link ResumeResultHelper#runSync}.
 *
 * <p>Root cause context (P123 approval-loop fix): a CLI {@code /approve} drives the resume
 * synchronously. The parked CLI command thread waits on {@link CliResultBridge}. The previous
 * behaviour cancelled the bridge at the start of the resume, which killed the parked waiter and
 * the terminal answer never surfaced (the CLI spun "Still working…" forever). The fix keeps the
 * HITL flag armed and calls {@code complete(rootSid, result)} so the parked waiter is delivered
 * the answer exactly once.
 *
 * <p>These tests assert that contract: on a terminal result the bridge is {@code complete(...)}d
 * (never {@code cancel(...)}ed), and the formatted answer is returned.
 */
class ResumeResultHelperTest {

    @Test
    void synchronousCliResumeCompletesParkedWaiterWithoutCancelling() throws Exception {
        CheckpointResumeHandler resumeHandler = mock(CheckpointResumeHandler.class);
        CliResultBridge bridge = mock(CliResultBridge.class);
        ResumeResultHelper helper = new ResumeResultHelper();
        helper.checkpointResumeHandler = resumeHandler;
        helper.cliResultBridge = bridge;
        AgentResult.Finished finished = new AgentResult.Finished("done", List.of(), 0, 0);
        when(resumeHandler.resumeAfterBatchDecisionSync("session-1", "coordinator", "user", "chat", "cli"))
                .thenReturn(finished);

        String response = helper.runSync("session-1", "coordinator", "user", "chat");

        assertTrue(response.contains("done"), "formatted terminal answer must be returned");
        // FIX: the parked CLI command's waiter must be completed (not cancelled) so the answer is
        // delivered and the CLI stops spinning "Still working". complete() dedups via its `signaled`
        // set if LoopExitPublisher also fires during the resume.
        verify(bridge).complete("session-1", finished);
        verify(bridge, never()).cancel("session-1");
        verify(bridge, never()).register("session-1");
    }

    @Test
    void synchronousCliResumeFallsBackToBridgeWaitOnNullResult() throws Exception {
        CheckpointResumeHandler resumeHandler = mock(CheckpointResumeHandler.class);
        CliResultBridge bridge = mock(CliResultBridge.class);
        ResumeResultHelper helper = new ResumeResultHelper();
        helper.checkpointResumeHandler = resumeHandler;
        helper.cliResultBridge = bridge;
        when(resumeHandler.resumeAfterBatchDecisionSync("session-2", "coordinator", "user", "chat", "cli"))
                .thenReturn(null);
        when(bridge.waitForResult("session-2", 60000L)).thenReturn(null);

        String response = helper.runSync("session-2", "coordinator", "user", "chat");

        assertNotNull(response);
        // The parked waiter path is preserved; the bridge must still not be cancelled.
        verify(bridge, never()).cancel("session-2");
    }
}
