package com.arcclaw.app.channel.telegram;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
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

    @Inject
    ChatMemoryStore chatMemoryStore;

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

    @Inject
    Instance<DevSecretSeeder> devSeeder;

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

        // Intercept /reset — wipes both workspace scope AND persistent chat memory
        if (text != null && text.trim().equalsIgnoreCase("/reset")) {
            log.infof("[TelegramHandler] User requested session reset for %s", baseId);

            // 1. Wipe the workspace scope (clears IN_PROGRESS / AWAITING_APPROVAL state)
            workspaceManager.terminateSession(baseId);

            // 2. Wipe persistent chat memory from SQLite so context poisoning is cleared
            chatMemoryStore.deleteMessages(baseId);

            log.infof("[TelegramHandler] Session %s fully reset — workspace and chat memory cleared", baseId);
            producerTemplate.sendBodyAndHeaders(
                    "telegram:bots?authorizationToken=" + botToken,
                    "🧹 Session wiped. Memory and tool states cleared. Ready for a new task.",
                    java.util.Map.of("CamelTelegramChatId", chatId)
            );
            return;
        }

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

            // Auto-cancel any dangling tool approvals if the user 
            // sends a free-text message instead of clicking /approve or /deny
            for (String r : java.util.List.of("coordinator", "coder", "researcher", "debugger")) {
                statefulAgentStore.load(baseId, r).ifPresent(cp -> {
                    if (cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                        log.infof("[TelegramHandler] User sent free-text. Auto-canceling dangling tool approval for role: %s", r);
                        cp.setStatus(CheckpointStatus.FAILED);
                        cp.failReason = "User interrupted with a new message. Tool execution aborted.";
                        cp.stateJson = "[]"; // Wipe the tool state
                        statefulAgentStore.save(cp);
                    }
                });
            }

            String activeRole = intentClassifier.classify(text);

            log.infof("[TelegramHandler] Intent classified '%s' -> role: %s",
                    text.length() > 50 ? text.substring(0, 50) + "..." : text, activeRole);

            // Session Continuity Check (Soft Reset only)
            statefulAgentStore.load(baseId, activeRole).ifPresent(cp -> {
                if (cp.getStatus() == CheckpointStatus.FAILED || cp.getStatus() == CheckpointStatus.COMPLETED) {
                    log.infof("Resetting ReAct state for session %s (%s) to start fresh goal.", baseId, cp.getStatus());
                    // 1. We keep the ChatMemory (DO NOT call terminateSession)
                    // 2. We just wipe the internal JSON tool-call array
                    cp.stateJson = "[]";
                    cp.failReason = null;
                    cp.setStatus(CheckpointStatus.IN_PROGRESS);
                    statefulAgentStore.save(cp);
                }
            });

            // 👉 THE FIX: Re-assert the scope and elevate autonomy AFTER the potential termination!
            WorkspaceScopeHolder.set(baseId);
            workspaceContext.setAutonomyLevel(autonomyLevel);

            // Dev DX: seed host OS tokens into vault (idempotent — skips if already seeded)
            if (!devSeeder.isUnsatisfied()) {
                devSeeder.get().seedDevSecrets();
            }

            log.infof("[TelegramHandler] Session %s | AutonomyLevel: %s | Model target: Groq", baseId, autonomyLevel);

            AgentResult result = agentLoop.run(baseId, activeRole, text, reasoningEngine, 5);

            if (result.awaitingApproval()) {
                reply(chatId, "⚠️ **Approval Required**\n\nThe agent is attempting a secure network operation. Please review the trace and reply with `/approve` or `/deny`.");
            } else if (result.success()) {
                reply(chatId, result.finalAnswer());
            } else {
                reply(chatId, "❌ **Task Failed**\n\nReason: " + result.terminationReason());
            }

        } catch (Exception e) {
            log.error("Failed to process agent loop for session: " + baseId, e);
            reply(chatId, "⚠️ System Error: " + e.getMessage());
        } finally {
            WorkspaceScopeHolder.clear();
        }
    }

    private String handleApprovalDecision(String chatId, String userId, String baseId, boolean approved) {
        WorkspaceScopeHolder.set(baseId);
        try {
            // Check which role is currently waiting for approval
            String activeRole = null;
            for (String role : java.util.List.of("coordinator", "coder", "researcher", "debugger")) {
                var cp = statefulAgentStore.load(baseId, role);
                if (cp.isPresent() && cp.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                    activeRole = role;
                    break;
                }
            }

            if (activeRole == null) {
                return "No pending tool executions require approval for this session.";
            }

            if (approved) {
                log.infof("[TelegramHandler] User approved pending tool execution for session: %s", baseId);
                workspaceContext.setAutonomyLevel(AutonomyLevel.FULL);

                // Dev DX: Seed tokens again upon approval resume in case JVM was restarted
                if (!devSeeder.isUnsatisfied()) {
                    devSeeder.get().seedDevSecrets();
                }

                AgentResult result = agentLoop.run(
                        baseId, activeRole,
                        "Resuming task after approval",
                        reasoningEngine, 5
                );

                if (result.success()) {
                    return result.finalAnswer();
                } else {
                    return "❌ **Task Failed after approval**\n\nReason: " + result.terminationReason();
                }
            } else {
                log.infof("[TelegramHandler] User denied pending tool execution for session: %s", baseId);
                AgentCheckpoint cp = statefulAgentStore.load(baseId, activeRole).orElseThrow();
                cp.setStatus(CheckpointStatus.FAILED);
                cp.failReason = "Human denied the tool execution.";
                statefulAgentStore.save(cp);
                return "🚫 Execution denied. The agent's task has been terminated.";
            }
        } catch (Exception e) {
            log.error("Error processing approval for session: " + baseId, e);
            return "⚠️ System Error during approval processing: " + e.getMessage();
        } finally {
            WorkspaceScopeHolder.clear();
        }
    }

    private void reply(String chatId, String message) {
        producerTemplate.sendBodyAndHeaders(
                "telegram:bots?authorizationToken=" + botToken,
                message,
                java.util.Map.of("CamelTelegramChatId", chatId)
        );
    }
}