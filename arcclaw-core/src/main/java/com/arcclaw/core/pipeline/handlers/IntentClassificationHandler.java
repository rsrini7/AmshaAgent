package com.arcclaw.core.pipeline.handlers;

import static com.arcclaw.core.message.UserMessages.*;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.IntentDetector;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.IntentClassificationService;
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
    
    private static final Pattern TRAILING_PUNCTUATION_PATTERN = Pattern.compile("[/?\\s]+$");

    @Inject IntentDetector classifier;
    @Inject DestructiveVocabularyFilter destructiveVocabularyFilter;
    @Inject ChannelPluginRegistry channelPluginRegistry;
    @Inject AgentRoleCache roleCache;
    @Inject IntentClassificationService intentDetector;

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
                    "- blocking before agent dispatch. chat=%s", context.getChatId());
            channelPluginRegistry.find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresent(s -> s.sendRaw(context.getUser(), DESTRUCTIVE_GUARD_REPLY));
            return HandleResult.STOP;
        }

        try {
            String role = null;
            String sid = context.getSessionId().orElseGet(() -> {
                log.debugf("No sessionId on context for continuity lookup for chat %s - using chatId", 
                    context.getChatId());
                return context.getChatId();
            });

            // Bug 5 Fix: Strip trailing punctuation (like '/') that can confuse classifiers
            String classificationInput = TRAILING_PUNCTUATION_PATTERN.matcher(message.text().trim()).replaceAll("");

            // §Issue 2: If goal was redacted, force simple chitchat to avoid tool-calling loops
            String currentGoal = context.getGoal();
            if (currentGoal != null && AgentSentinels.REDACTED_HYPHEN_PATTERN.matcher(currentGoal).find()) {
                log.infof("PII-redacted goal detected for chat %s - forcing coordinator chitchat", context.getChatId());
                context.setData(AgentSentinels.DATA_IS_SIMPLE_CHITCHAT, true);
                context.setData(AgentSentinels.DATA_IS_PII_REDACTED, true);
                context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
                return HandleResult.CONTINUE;
            }

            String previousRole = roleCache.findLatestRole(sid).orElse(null);

            // Bug 2 Fix: Short follow-up heuristics for sticky roles
            boolean isFollowUpHeuristic = intentDetector.isFollowUpHeuristic(classificationInput);

            if (isFollowUpHeuristic && previousRole != null && 
               (AgentRoleEnum.CODER.toScopedName().equals(previousRole) || 
                AgentRoleEnum.DEBUGGER.toScopedName().equals(previousRole))) {
                
                // §Bug-2 Fix: Tool-availability gate. If goal implies scheduling, coder role lacks the tool.
                if (intentDetector.isScheduleIntent(classificationInput)) {
                    log.infof("Goal requires scheduling, overriding sticky role %s to coordinator for chat %s", previousRole, context.getChatId());
                    role = AgentRoleEnum.COORDINATOR.toScopedName();
                } else {
                    log.infof("Short follow-up detected for chat %s - preserving sticky role: %s", context.getChatId(), previousRole);
                    role = previousRole;
                }
            } else {
                role = classifier.classify(classificationInput);
            }

            if ("__CONTINUE__".equals(role)) {
                log.infof("Handling continuity request for session %s - previous role: %s",
                    sid, previousRole);
                if (previousRole != null) {
                    role = previousRole;
                } else {
                    role = AgentRoleEnum.COORDINATOR.toScopedName();
                }
            }

            // §78-fix-3: null guard - openrouter/auto occasionally returns null
            // causing NPE in downstream .trim() calls
            if (role == null || role.isBlank()) {
                log.warnf("IntentDetector returned null/blank for chat %s - defaulting to coordinator",
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
                log.warnf("Intent classifier rate limited for chat %s - defaulting to coordinator",
                    context.getChatId());
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(),
                        CLASSIFICATION_RATE_LIMITED));
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
            log.warnf("Intent classification failed for chat %s - defaulting to coordinator: %s",
                context.getChatId(), e.getMessage());
            context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        }

        return HandleResult.CONTINUE;
    }
}
