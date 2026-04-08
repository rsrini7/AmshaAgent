package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.agent.loop.AgentRoleCache;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.security.DestructiveVocabularyFilter;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import dev.langchain4j.exception.HttpException;
import org.jboss.logging.Logger;

/**
 * Classifies free-text messages to determine the agent role.
 */
@ApplicationScoped
public class IntentClassificationHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(IntentClassificationHandler.class);
    
    private static final java.util.Set<String> FOLLOWUP_SIGNALS = java.util.Set.of(
        "same", "again", "retry", "execute", "run", "use sandbox", 
        "in java", "in go", "in python", "in typescript", "in kotlin", "in rust"
    );

    @Inject IntentClassifier classifier;
    @Inject DestructiveVocabularyFilter destructiveVocabularyFilter;
    @Inject ChannelPluginRegistry channelPluginRegistry;
    @Inject AgentRoleCache roleCache;

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
        if (message == null || message.text() == null || message.text().isBlank()) {
            context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
            return HandleResult.CONTINUE;
        }

        // DESTRUCTIVE VOCABULARY GUARD
        if (destructiveVocabularyFilter.isDestructive(message.text())) {
            log.warnf("[IntentClassificationHandler] Destructive vocabulary detected " +
                    "— blocking before agent dispatch. chat=%s", context.getChatId());
            String safeReply = "⛔ I can only perform read operations (view, list). " +
                    "Destructive operations such as deleting repositories are not supported.";
            channelPluginRegistry.find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresent(s -> s.sendRaw(context.getUser(), safeReply));
            return HandleResult.STOP;
        }

        try {
            String role = null;
            String sid = context.getSessionId().orElseGet(() -> {
                log.debugf("No sessionId on context for continuity lookup for chat %s — using chatId", 
                    context.getChatId());
                return context.getChatId();
            });
            String previousRole = roleCache.findLatestRole(sid).orElse(null);

            // Bug 2 Fix: Short follow-up heuristics for sticky roles
            String lowerMsg = message.text().toLowerCase();
            boolean isFollowUpHeuristic = lowerMsg.length() < 150 && 
                FOLLOWUP_SIGNALS.stream().anyMatch(lowerMsg::contains);

            if (isFollowUpHeuristic && previousRole != null && 
               (AgentRoleEnum.CODER.toScopedName().equals(previousRole) || 
                AgentRoleEnum.DEBUGGER.toScopedName().equals(previousRole))) {
                log.infof("Short follow-up detected for chat %s — preserving sticky role: %s", context.getChatId(), previousRole);
                role = previousRole;
            } else {
                role = classifier.classify(message.text());
            }

            if ("__CONTINUE__".equals(role)) {
                log.infof("Handling continuity request for session %s — previous role: %s",
                    sid, previousRole);
                if (previousRole != null) {
                    role = previousRole;
                } else {
                    role = AgentRoleEnum.COORDINATOR.toScopedName();
                }
            }

            // §78-fix-3: null guard — openrouter/auto occasionally returns null
            // causing NPE in downstream .trim() calls
            if (role == null || role.isBlank()) {
                log.warnf("IntentClassifier returned null/blank for chat %s — defaulting to coordinator",
                    context.getChatId());
                context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
                return HandleResult.CONTINUE;
            }

            // Secondary guard: destructive-vocabulary role override
            role = destructiveVocabularyFilter.filter(role, message.text());

            context.setAgentRole(role);
            log.infof("Classified message as role '%s' for chat %s", role, context.getChatId());

        } catch (HttpException e) {
            if (e.statusCode() == 429 ||
                    (e.getMessage() != null && e.getMessage().contains("rate limit"))) {
                log.warnf("Intent classifier rate limited for chat %s — defaulting to coordinator",
                    context.getChatId());
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(),
                        "⏳ Rate limited on classification — defaulting to coordinator."));
            } else {
                String sanitizedMsg = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
                log.errorf("Intent classification failed (HTTP %d) for chat %s: %s",
                        e.statusCode(), context.getChatId(), sanitizedMsg);
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), sanitizedMsg));
            }
            context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        } catch (Exception e) {
            log.warnf("Intent classification failed for chat %s — defaulting to coordinator: %s",
                context.getChatId(), e.getMessage());
            context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        }

        return HandleResult.CONTINUE;
    }
}
