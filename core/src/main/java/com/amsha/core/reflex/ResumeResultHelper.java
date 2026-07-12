package com.amsha.core.reflex;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.state.CheckpointStatus;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentResultFormatter;

@Dependent
public class ResumeResultHelper {

    @Inject
    CheckpointResumeHandler checkpointResumeHandler;

    @Inject
    CliResultBridge cliResultBridge;

    @Inject
    StatefulAgentStore statefulAgentStore;

    public String runSync(String sessionId, String activeRole, String userId, String chatId) {
        // A CLI /approve or /deny renders the synchronous reflex response itself.
        // Cancel the interactive waiter's future before resuming so LoopExitPublisher
        // cannot wake it and print the same terminal result a second time.
        cliResultBridge.cancel(sessionId);
        try {
            AgentResult result =
                    checkpointResumeHandler.resumeAfterBatchDecisionSync(sessionId, activeRole, userId, chatId, "cli");
            if (result == null) result = cliResultBridge.waitForResult(sessionId, 10000);
            return result != null ? AgentResultFormatter.format(result) : "⏱ Timed out waiting for session.";
        } catch (Exception e) {
            return "❌ Resume failed: " + e.getMessage();
        } finally {
            cliResultBridge.cancel(sessionId);
        }
    }

    public String pollBridge(String sessionId) {
        var _ = cliResultBridge.register(sessionId);
        try {
            AgentResult bridgeResult = cliResultBridge.waitForResult(sessionId, 200);
            return bridgeResult != null ? AgentResultFormatter.format(bridgeResult) : null;
        } finally {
            cliResultBridge.cancel(sessionId);
        }
    }

    public String handleFailedRetry(String sessionId, String failedRetryMsg) {
        if (failedRetryMsg != null) {
            var lastCp = statefulAgentStore.findLatestCheckpoint(sessionId);
            if (lastCp.isPresent() && lastCp.get().getStatus() == CheckpointStatus.FAILED) {
                return failedRetryMsg;
            }
        }
        return null;
    }
}
