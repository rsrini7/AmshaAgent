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

        final String[] activeSessionId = { baseId };

        // Session Continuity Check (REQ-V11-CLI-11)
        statefulAgentStore.load(baseId, "coordinator").ifPresent(cp -> {
            if (cp.status == CheckpointStatus.COMPLETED || cp.status == CheckpointStatus.FAILED) {
                // The task finished in the past. We want to start a completely new context
                // instead of appending hallucinated steps to a finalized sequence.
                activeSessionId[0] = UUID.randomUUID().toString();
            }
        });

        try {
            // Activate CDI Workspace Scope manually
            WorkspaceScopeHolder.set(activeSessionId[0]);
            workspaceContext.setAutonomyLevel(autonomyLevel);

            log.infof(
                    "[TelegramHandler] Session %s | AutonomyLevel: %s | Model target: Groq",
                    activeSessionId[0], autonomyLevel);

            AgentResult result = agentLoop.run(
                    activeSessionId[0],
                    "coordinator",
                    text,
                    reasoningEngine,
                    5);

            reply(chatId, result.finalAnswer());

        } finally {
            WorkspaceScopeHolder.clear();
        }
    }

    private String handleApprovalDecision(String chatId, String userId, String sessionId, boolean approved) {
        var checkpoint = statefulAgentStore.load(sessionId, "coordinator");

        if (checkpoint.isEmpty() || checkpoint.get().status != CheckpointStatus.AWAITING_APPROVAL) {
            return "❌ No pending approval found for your session.";
        }

        if (!approved) {
            statefulAgentStore.markFailed(checkpoint.get().id, "Denied by user via Telegram");
            return "🚫 Operation denied. The task has been terminated.";
        }

        log.infof("[TelegramHandler] User %s approved operation for session %s. Elevating to FULL.",
                userId, sessionId);

        try {
            WorkspaceScopeHolder.set(sessionId);
            workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);

            AgentResult result = agentLoop.run(
                    sessionId,
                    "coordinator",
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
