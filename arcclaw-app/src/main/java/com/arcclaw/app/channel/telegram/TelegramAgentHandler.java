package com.arcclaw.app.channel.telegram;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Handles Telegram messages using Camel and Camel-Quarkus-Telegram.
 * Offloads long-running agent loops to a ManagedExecutor to avoid blocking
 * Vert.x threads.
 */
@ApplicationScoped
public class TelegramAgentHandler {

    @Inject
    AgentLoop agentLoop;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    ReasoningEngine reasoningEngine;

    @Inject
    ManagedExecutor executor;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
    String botToken;

    @Inject
    WorkspaceContext workspaceContext;

    @ConfigProperty(name = "arcclaw.autonomy.default-level", defaultValue = "SUPERVISED")
    AutonomyLevel autonomyLevel;

    @Inject
    org.jboss.logging.Logger log;

    @Inject
    IntentClassifierService intentClassifier;

    /**
     * Executes on the Vert.x worker thread directly from the Camel route.
     * All Camel Exchange data must be extracted synchronously before offloading
     * to the ManagedExecutor, as Exchange instances are not thread-safe.
     */
    public void receiveMessage(Exchange exchange) {
        IncomingMessage msg = exchange.getIn().getBody(IncomingMessage.class);
        if (msg == null || msg.getText() == null) {
            log.warn("Received message without text");
            return;
        }

        String text = msg.getText();
        String userId = String.valueOf(msg.getFrom().getId());
        String chatId = exchange.getIn().getHeader("CamelTelegramChatId", String.class);

        log.infof("Received Telegram message from user %s in chat %s: %s", userId, chatId, text);

        // Offload execution specifically to allow the Vert.x networking thread
        // to return immediately, preventing event loop blocking.
        executor.submit(() -> runAgent(chatId, userId, text));
    }

    /**
     * Executes asynchronously on the MicroProfile ManagedExecutor thread pool.
     * The {@link ActivateRequestContext} annotation hydrates the CDI scopes
     * required by the Virtual Thread and the underlying persistence layer.
     */
    @ActivateRequestContext
    void runAgent(String chatId, String userId, String text) {
        // Derive base UUID deterministically
        String baseId = UUID.nameUUIDFromBytes((chatId + ":" + userId).getBytes(StandardCharsets.UTF_8)).toString();

        // Handle commands first
        if (text.equalsIgnoreCase("/approve")) {
            reply(chatId, handleApprovalDecision(chatId, userId, baseId, true));
            return;
        }
        if (text.equalsIgnoreCase("/deny")) {
            reply(chatId, handleApprovalDecision(chatId, userId, baseId, false));
            return;
        }

        try {
            // Activate CDI Workspace Scope initially so IntentClassifier works
            WorkspaceScopeHolder.set(baseId);

            String activeRole = intentClassifier.classify(text);
            log.infof("[TelegramHandler] Intent classified '%s' -> role: %s",
                    text.length() > 50 ? text.substring(0, 50) + "..." : text, activeRole);

            // Session Continuity Check (Wipe old state instead of changing the ID)
            statefulAgentStore.load(baseId, activeRole).ifPresent(cp -> {
                if (cp.getStatus() == CheckpointStatus.COMPLETED || cp.getStatus() == CheckpointStatus.FAILED) {
                    log.infof("Previous session %s was %s. Wiping history to start fresh.", baseId, cp.getStatus());

                    // 1. Wipe the LangChain4j ChatMemory (THIS DESTROYS THE CDI SCOPE!)
                    workspaceManager.terminateSession(baseId);

                    // 2. Wipe the ArcClaw Reasoning History so it doesn't poison the next prompt
                    cp.stateJson = "[]"; 
                    cp.failReason = null;
                    cp.setStatus(CheckpointStatus.IN_PROGRESS);
                    statefulAgentStore.save(cp);
                }
            });

            // 👉 THE FIX: Re-assert the scope and elevate autonomy AFTER the potential termination!
            WorkspaceScopeHolder.set(baseId);
            workspaceContext.setAutonomyLevel(autonomyLevel);

            log.infof("[TelegramHandler] Session %s | AutonomyLevel: %s | Model target: Groq", baseId, autonomyLevel);

            AgentResult result = agentLoop.run(
                    baseId,
                    activeRole,
                    text,
                    reasoningEngine,
                    5);

            reply(chatId, result.finalAnswer());

        } finally {
            WorkspaceScopeHolder.clear();
        }
    }

    private String handleApprovalDecision(String chatId, String userId, String sessionId, boolean approved) {
        String pausedRole = null;
        com.arcclaw.core.state.AgentCheckpoint pendingCheckpoint = null;

        // Discover which agent role is waiting for approval
        for (String role : java.util.List.of("coordinator", "coder", "researcher", "debugger")) {
            var opt = statefulAgentStore.load(sessionId, role);
            if (opt.isPresent() && opt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                pausedRole = role;
                pendingCheckpoint = opt.get();
                break;
            }
        }

        if (pausedRole == null) {
            return "❌ No pending approval found for your session.";
        }

        if (!approved) {
            statefulAgentStore.markFailed(pendingCheckpoint.id, "Denied by user via Telegram");
            return "🚫 Operation denied. The task has been terminated.";
        }

        log.infof("[TelegramHandler] User %s approved operation for session %s. Elevating to FULL.",
                userId, sessionId);

        try {
            WorkspaceScopeHolder.set(sessionId);
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);

            AgentResult result = agentLoop.run(
                    sessionId,
                    pausedRole,
                    "Resuming task after approval",
                    reasoningEngine,
                    5);

            return "✅ Approved. Resuming...\n\n" + result.finalAnswer();

        } finally {
            // Memory cleanup ONLY — session remains IN_PROGRESS indefinitely for
            // conversation memory
            WorkspaceScopeHolder.clear();
        }
    }

    private void reply(String chatId, String text) {
        producerTemplate.sendBodyAndHeader(
                "telegram:bots?authorizationToken=" + botToken,
                text,
                "CamelTelegramChatId",
                chatId);
    }
}
