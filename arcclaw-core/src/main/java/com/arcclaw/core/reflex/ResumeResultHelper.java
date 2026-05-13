package com.arcclaw.core.reflex;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.CheckpointResumeHandler;
import com.arcclaw.core.agent.loop.CliResultBridge;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;

@Dependent
public class ResumeResultHelper {

    @Inject CheckpointResumeHandler checkpointResumeHandler;
    @Inject CliResultBridge cliResultBridge;
    @Inject StatefulAgentStore statefulAgentStore;

    public String runSync(String sessionId, String activeRole, String userId, String chatId) {
        cliResultBridge.register(sessionId);
        try {
            AgentResult result = checkpointResumeHandler.resumeAfterBatchDecisionSync(
                    sessionId, activeRole, userId, chatId, "cli");
            if (result == null) result = cliResultBridge.waitForResult(sessionId, 10000);
            return result != null ? AgentResultFormatter.format(result) : "⏱ Timed out waiting for session.";
        } catch (Exception e) {
            return "❌ Resume failed: " + e.getMessage();
        } finally {
            cliResultBridge.cancel(sessionId);
        }
    }

    public String pollBridge(String sessionId) {
        cliResultBridge.register(sessionId);
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
