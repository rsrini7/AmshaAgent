package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.channel.api.ChannelType;
import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.state.SessionTree;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.tool.api.AgentSentinels;

import org.jboss.logging.Logger;

/**
 * Single exit-point publisher for all agent loop termination signals.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Map {@link AgentResult} → wire status string and fire {@link AgentStateChangedEvent} (CDI).</li>
 *   <li>Signal the {@link CliResultBridge} for CLI-channel sessions.</li>
 * </ol>
 *
 * <p>Architecture invariants enforced here:
 * <ul>
 *   <li>{@link AgentStateMachineRunner} MUST NOT fire {@link AgentStateChangedEvent} directly.</li>
 *   <li>All status-string mapping lives exclusively in this class.</li>
 *   <li>Delegate sub-sessions ({@link AgentSentinels#DELEGATE_SESSION_SENTINEL}) are
 *       never published - they have no UI observer.</li>
 *   <li>{@link AgentResult.AwaitingApproval} is never forwarded to the CLI bridge
 *       (approval pauses are handled by the HITL path).</li>
 * </ul>
 */
@ApplicationScoped
public class LoopExitPublisher {

    private static final Logger log = Logger.getLogger(LoopExitPublisher.class);

    @Inject
    DomainEventBus eventBus;

    @Inject
    CliResultBridge cliResultBridge;

    @Inject
    StatefulAgentStore agentStore;

    /**
     * Publish the initial loop start signal.
     * Called by {@link AgentStateMachineRunner} exactly once per {@code run()} invocation.
     */
    public void publishStart(AgentExecutionContext context) {
        // Delegate sub-sessions skip start signals to avoid UI noise
        if (context.sessionId().contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) return;

        eventBus.publish(
                EventTypeCatalog.AGENT_STATE_CHANGED,
                new AgentStateChangedEvent(context.sessionId(), context.agentRole(), "active", context.goal()));
    }

    /**
     * Publish a terminal loop exit.
     * Called by {@link AgentStateMachineRunner} at every {@code return} that carries a final result.
     *
     * @param context the execution context for the current loop run
     * @param result  the final {@link AgentResult} - must not be {@code null}
     */
    public void publish(AgentExecutionContext context, AgentResult result) {
        fireStateEvent(context.sessionId(), context.agentRole(), context.goal(), result);
        signalBridgeIfCli(context, result);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void fireStateEvent(String sessionId, String agentRole, String goal, AgentResult result) {
        String effectiveSessionId = sessionId;
        if (sessionId != null && sessionId.contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) {
            // Approval pauses belong to the child so the UI can show exactly which
            // delegated worker is blocked. Once the child finishes, retain the
            // historical parent-session terminal event used by the coordinator UI.
            if (!(result instanceof AgentResult.AwaitingApproval)) {
                effectiveSessionId = SessionTree.parentOf(sessionId).orElse(sessionId);
            }
        }

        String status = delegatedChildWaiting(sessionId, result) ? "waiting-delegate" : toWireStatus(result);
        try {
            eventBus.publish(
                    EventTypeCatalog.AGENT_STATE_CHANGED,
                    new AgentStateChangedEvent(effectiveSessionId, agentRole, status, goal));
        } catch (Exception e) {
            // CDI observer failures must never corrupt the result pipeline.
            log.warnf(
                    "LoopExitPublisher: stateChangedEvent fire failed for session %s - %s",
                    effectiveSessionId, e.getMessage());
        }
    }

    private boolean delegatedChildWaiting(String sessionId, AgentResult result) {
        if (!(result instanceof AgentResult.AwaitingApproval)
                || sessionId == null
                || sessionId.contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)
                || agentStore == null) return false;
        return agentStore.findActiveCheckpointsInSessionTree(sessionId).stream()
                .anyMatch(cp -> cp != null
                        && cp.getSessionId() != null
                        && SessionTree.isDelegatedChild(sessionId, cp.getSessionId())
                        && cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL);
    }

    private void signalBridgeIfCli(AgentExecutionContext context, AgentResult result) {
        if (context == null) return;
        // Delegate sub-sessions don't use the CLI bridge.
        if (context.sessionId().contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) return;
        // Approval pauses are handled by the HITL path, not the CLI bridge. Mark the
        // session as awaiting approval so a later completion is allowed to buffer/signal
        // the bridge (and stale normal-turn results are not replayed).
        boolean isCli = (context.channelType() != null && ChannelType.CLI.id().equalsIgnoreCase(context.channelType()))
                || (context.sessionId() != null && context.sessionId().startsWith(AgentSentinels.CLI_SESSION_PREFIX));
        if (!isCli) return;

        if (result instanceof AgentResult.AwaitingApproval) {
            // The bridge is CLI-only; only mark a pending approval for CLI sessions.
            // Web/Telegram deliver the approval and its result through their own senders.
            cliResultBridge.markAwaitingApproval(context.sessionId());
            return;
        }

        cliResultBridge.complete(context.sessionId(), result);
    }

    /**
     * Canonical mapping from {@link AgentResult} sealed type → wire status string.
     * This is the ONLY place in the codebase where this mapping exists.
     */
    static String toWireStatus(AgentResult result) {
        return switch (result) {
            case AgentResult.Finished _ -> "idle";
            case AgentResult.StructuredFinished _ -> "idle";
            case AgentResult.AwaitingApproval _ -> "waiting-approval";
            case AgentResult.AwaitingRetry _ -> "waiting-retry";
            default -> "error";
        };
    }
}
