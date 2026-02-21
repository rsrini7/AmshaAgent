package com.arcclaw.app.channel.telegram;

import com.arcclaw.core.agent.AgentLoop;
import com.arcclaw.core.agent.AgentResult;
import com.arcclaw.core.agent.ReasoningEngine;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
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

    /**
     * Executes on the Vert.x worker thread directly from the Camel route.
     * All Camel Exchange data must be extracted synchronously before offloading
     * to the ManagedExecutor, as Exchange instances are not thread-safe.
     */
    public void receiveMessage(Exchange exchange) {
        IncomingMessage msg = exchange.getIn().getBody(IncomingMessage.class);
        if (msg == null || msg.getText() == null) {
            return;
        }

        String text = msg.getText();
        String userId = String.valueOf(msg.getFrom().getId());
        String chatId = exchange.getIn().getHeader("CamelTelegramChatId", String.class);

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
        // Derive base UUID deterministically so we map identically on restart
        String baseId = UUID.nameUUIDFromBytes((chatId + ":" + userId).getBytes(StandardCharsets.UTF_8)).toString();

        // Mutable wrapper to allow updating inside the lambda
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

            AgentResult result = agentLoop.run(
                    activeSessionId[0],
                    "coordinator",
                    text,
                    reasoningEngine,
                    5);

            // Use ProducerTemplate to reply directly to the originating Chat ID
            // without needing an outgoing Camel route
            producerTemplate.sendBodyAndHeader(
                    "telegram:bots?authorizationToken=" + botToken,
                    result.finalAnswer(),
                    "CamelTelegramChatId",
                    chatId);

        } finally {
            // Memory cleanup ONLY — session remains IN_PROGRESS indefinitely for
            // conversation memory
            WorkspaceScopeHolder.clear();
        }
    }
}
