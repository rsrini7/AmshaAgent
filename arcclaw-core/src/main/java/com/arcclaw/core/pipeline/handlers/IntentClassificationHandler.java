package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.security.DestructiveVocabularyFilter;
import dev.langchain4j.exception.HttpException;
import org.jboss.logging.Logger;

/**
 * Classifies free-text messages to determine the agent role.
 */
@ApplicationScoped
public class IntentClassificationHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(IntentClassificationHandler.class);

    @Inject
    IntentClassifier classifier;

    @Inject
    DestructiveVocabularyFilter destructiveVocabularyFilter;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        if (context.getAgentRole().isPresent()) {
            return HandleResult.CONTINUE;
        }

        ChannelMessage message = context.getMessage();
        if (message != null && message.text() != null) {

            // ── DESTRUCTIVE VOCABULARY GUARD ──────────────────────────────
            // Block before any LLM call. coordinator still has github tool,
            // so overriding role is not enough — we must stop the pipeline.
            if (destructiveVocabularyFilter.isDestructive(message.text())) {
                log.warnf("[IntentClassificationHandler] Destructive vocabulary detected " +
                        "— blocking before agent dispatch. chat=%s", context.getChatId());
                String safeReply = "⛔ I can only perform read operations (view, list). " +
                        "Destructive operations such as deleting repositories are not supported.";
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), safeReply));
                return HandleResult.STOP;    // ← short-circuits AgentDispatchHandler entirely
            }
            // ── END DESTRUCTIVE VOCABULARY GUARD ──────────────────────────

            try {
                String role = classifier.classify(message.text());

                // Apply destructive-vocabulary role override as secondary guard
                // (handles edge cases where isDestructive missed but role is tool-capable)
                role = destructiveVocabularyFilter.filter(role, message.text());  // ← ADD

                context.setAgentRole(role);
                log.infof("Classified message as role '%s' for chat %s", role, context.getChatId());
            } catch (HttpException e) {
                if (e.statusCode() == 429 || (e.getMessage() != null && e.getMessage().contains("rate limit"))) {
                    log.warnf("Intent classifier rate limited for chat %s — defaulting to coordinator: %s",
                            context.getChatId(), e.getMessage());
                    channelPluginRegistry.find(context.getChannelType())
                        .map(ChannelPlugin::sender)
                        .ifPresent(s -> s.sendRaw(context.getUser(), "⏳ Rate limited on classification — defaulting to coordinator."));
                    context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
                } else {
                    throw e; // re-throw non-rate-limit HTTP errors
                }
            } catch (Exception e) {
                log.warnf("Intent classification failed, defaulting to coordinator: %s", e.getMessage());
                context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
            }
        } else {
            // Default for non-text messages if needed, though they usually shouldn't reach here without a role
            context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        }

        return HandleResult.CONTINUE;
    }
}
