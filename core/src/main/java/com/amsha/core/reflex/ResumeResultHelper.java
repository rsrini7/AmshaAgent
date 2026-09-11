package com.amsha.core.reflex;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.core.agent.loop.CheckpointResumeHandler;
import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.state.SessionTree;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.AgentResultFormatter;
import com.amsha.persistence.api.CheckpointStatus;

@Dependent
public class ResumeResultHelper {

    @Inject
    CheckpointResumeHandler checkpointResumeHandler;

    @Inject
    CliResultBridge cliResultBridge;

    @Inject
    StatefulAgentStore statefulAgentStore;

    public String runSync(String sessionId, String activeRole, String userId, String chatId) {
        // A CLI /approve or /deny drives the resume synchronously and renders the reflex
        // response itself. Keep the originally-parked CLI command's waiter alive and the
        // HITL flag armed so the terminal result produced by this resume (or by
        // LoopExitPublisher during it) is delivered to it — otherwise the CLI command
        // spins "Still working" forever and never surfaces the answer.
        String rootSid = SessionTree.rootOf(sessionId);
        cliResultBridge.markAwaitingApproval(rootSid);
        try {
            AgentResult result =
                    checkpointResumeHandler.resumeAfterBatchDecisionSync(sessionId, activeRole, userId, chatId, "cli");
            if (result == null) result = cliResultBridge.waitForResult(rootSid, 60000);
            if (result != null && !(result instanceof AgentResult.AwaitingApproval)) {
                // Terminal completion (possibly reached via DelegatedCompletionHandoff resuming
                // the parent coordinator): deliver to the parked CLI command so it unblocks.
                // complete() dedups via `signaled`, so a concurrent LoopExitPublisher signal is ignored.
                cliResultBridge.complete(rootSid, result);
            }
            return result != null ? AgentResultFormatter.format(result) : "⏱ Timed out waiting for session.";
        } catch (Exception e) {
            // Do not leave the parked CLI waiter spinning when the synchronous
            // resume fails before LoopExitPublisher can deliver a terminal result.
            cliResultBridge.complete(
                    rootSid,
                    AgentResult.failed(
                            AgentErrorMessageUtil.safeMessage(e), AgentResult.FailureCategory.SYSTEM_ERROR, List.of()));
            return "❌ Resume failed: " + AgentErrorMessageUtil.safeMessage(e);
        }
    }

    public String pollBridge(String sessionId) {
        String rootSid = SessionTree.rootOf(sessionId);
        try {
            AgentResult bridgeResult = cliResultBridge.waitForResult(rootSid, 200);
            return bridgeResult != null ? AgentResultFormatter.format(bridgeResult) : null;
        } catch (Exception _) {
            return null;
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
