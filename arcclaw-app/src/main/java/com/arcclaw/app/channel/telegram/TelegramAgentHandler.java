package com.arcclaw.app.channel.telegram;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.AgentRoleEnum;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.agent.RoleRegistry;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentErrorMessageUtil;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DevSecretSeeder;
import com.arcclaw.app.security.TelegramAllowlistGuard;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.state.ContextCompactionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.MDC;

import java.lang.ScopedValue;
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
    ContextCompactionService compactionService;

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

    @Inject
    ReflexRegistry reflexRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    TelegramAllowlistGuard allowlistGuard;

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
        String username = msg.getFrom().getUsername();
        String chatId = exchange.getIn().getHeader("CamelTelegramChatId", String.class);

        log.infof("Received Telegram message from user %s (@%s) in chat %s: %s", userId, username, chatId, text);

        if (!allowlistGuard.isAllowed(msg.getFrom().getId(), username)) {
            if (!allowlistGuard.isSilentBlock()) {
                reply(chatId, allowlistGuard.getBlockResponse());
            }
            log.warnf("[SECURITY] Blocked unauthorized Telegram user: %s (id: %s)", username, userId);
            return;
        }

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

        ScopedValue.where(WorkspaceScopeHolder.USER_ID, userId)
                   .where(WorkspaceScopeHolder.CHAT_ID, chatId)
                   .run(() -> WorkspaceScopeHolder.run(baseId, () -> {
            try {
                // Push userId to MDC for Audit logs
                MDC.put("userId", userId);

                // --- REFLEX INTERCEPTOR: O(1) Deterministic Bypass ---
                ReflexResult reflexResult = reflexRegistry.processIfReflex(baseId, userId, text);
                if (reflexResult.commandResponse().isPresent()) {
                    reply(chatId, reflexResult.commandResponse().get());
                    return; // Terminate execution immediately, bypassed LLM
                }

                String safeInput = reflexResult.sanitizedInput();

                // Auto-cancel any dangling tool approvals if the user
                // sends a free-text message instead of clicking /approve or /deny
                for (AgentRoleEnum r : AgentRoleEnum.values()) {
                    statefulAgentStore.load(baseId, r.toScopedName()).ifPresent(cp -> {
                        if (cp.getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                            log.infof("[TelegramHandler] User sent free-text. Auto-canceling dangling tool approval for role: %s", r.toScopedName());
                            cp.setStatus(CheckpointStatus.FAILED);
                            cp.setFailReason("User interrupted with a new message. Tool execution aborted.");
                            cp.setStateJson("[]");
                            cp.setPendingToolName(null);
                            cp.setPendingToolParametersJson(null);
                            statefulAgentStore.save(cp);
                            log.infof("[TelegramHandler] Successfully auto-canceled tool approval for role: %s", r.toScopedName());
                        }
                    });
                }

                String classifiedRole = intentClassifier.classify(safeInput);

                AgentRoleEnum resolvedRole;
                try {
                    resolvedRole = AgentRoleEnum.fromString(classifiedRole);
                } catch (IllegalArgumentException e) {
                    resolvedRole = AgentRoleEnum.COORDINATOR;
                    log.warnf("[TelegramHandler] Unknown role classification '%s', defaulting to COORDINATOR", classifiedRole);
                }

                final String activeRole = resolvedRole.toScopedName();

                log.infof("[TelegramHandler] Intent classified '%s' -> original: %s | active: %s (enum: %s)",
                        safeInput.length() > 50 ? safeInput.substring(0, 50) + "..." : safeInput, classifiedRole, activeRole, resolvedRole);

                // Session Continuity Check (Soft Reset only)
                statefulAgentStore.load(baseId, activeRole).ifPresent(cp -> {
                    if (cp.getStatus() == CheckpointStatus.FAILED || cp.getStatus() == CheckpointStatus.COMPLETED) {
                        log.infof("Resetting ReAct state for session %s (%s) to start fresh goal.", baseId, cp.getStatus());
                        // 1. We keep the ChatMemory (DO NOT call terminateSession)
                        // 2. We just wipe the internal JSON tool-call array
                        cp.setStateJson("[]");
                        cp.setFailReason(null);
                        cp.setPendingToolName(null);
                        cp.setPendingToolParametersJson(null);
                        cp.setStatus(CheckpointStatus.IN_PROGRESS);
                        statefulAgentStore.save(cp);
                    }
                });

                // Re-assert the scope and elevate autonomy AFTER the potential termination!
                workspaceContext.setAutonomyLevel(autonomyLevel);

                // Dev DX: seed host OS tokens into vault (idempotent — skips if already seeded)
                if (!devSeeder.isUnsatisfied()) {
                    devSeeder.get().seedDevSecrets();
                }

                log.infof("[TelegramHandler] Session %s | AutonomyLevel: %s | Model target: Groq", baseId, autonomyLevel);

                try {
                    compactionService.compactIfNeeded(baseId, userId);
                } catch (Exception e) {
                    log.warnf("Compaction failed for session=%s, proceeding without: %s", baseId, e.getMessage());
                }

                AgentResult result = agentLoop.run(baseId, activeRole, safeInput, reasoningEngine, roleRegistry.defaultMaxIterations(resolvedRole));

                if (result.awaitingApproval()) {
                    String approvalDetail = statefulAgentStore.load(baseId, activeRole)
                        .map(cp -> {
                            String tool = cp.getPendingToolName() != null
                                ? cp.getPendingToolName() : "unknown";
                            String params = cp.getPendingToolParametersJson() != null
                                ? cp.getPendingToolParametersJson() : "{}";
                            return String.format(
                                "🤖 *Agent:* %s\n" +
                                "🔧 *Tool:* %s\n" +
                                "📋 *Parameters:* `%s`",
                                activeRole, tool, params);
                        })
                        .orElse("⚠️ Tool details unavailable.");

                    reply(chatId,
                        "⚠️ *Approval Required*\n\n" +
                        approvalDetail + "\n\n" +
                        "Reply `/approve` to allow or `/deny` to cancel.");
                } else if (result.success()) {
                    reply(chatId, result.finalAnswer());
                } else {
                    reply(chatId, "❌ **Task Failed**\n\nReason: " + AgentErrorMessageUtil.sanitizeErrorMessage(result.terminationReason()));
                }

            } catch (Exception e) {
                log.errorf(e, "Unhandled exception in runAgent for session=%s", baseId);
                reply(chatId, "⚠️ An unexpected error occurred. Please try again.");
            } finally {
                MDC.remove("userId");
            }
        }));
    }


    private void reply(String chatId, String message) {
        producerTemplate.sendBodyAndHeaders(
                "telegram:bots?authorizationToken=" + botToken,
                message,
                new java.util.HashMap<>(java.util.Map.of("CamelTelegramChatId", chatId))
        );
    }
}