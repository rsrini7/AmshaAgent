package com.amsha.core.pipeline.handlers;

import static com.amsha.core.message.UserMessages.*;

import dev.langchain4j.exception.HttpException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.IntentDetector;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentRoleCache;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessageHandler;
import com.amsha.core.security.DestructiveVocabularyFilter;
import com.amsha.core.util.AgentErrorMessageUtil;

import org.jboss.logging.Logger;

/**
 * Classifies free-text messages to determine the agent role.
 */
@ApplicationScoped
public class IntentClassificationHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(IntentClassificationHandler.class);

    private static final Pattern TRAILING_PUNCTUATION_PATTERN = Pattern.compile("[/?\\s]+$");

    @Inject
    IntentDetector classifier;

    @Inject
    DestructiveVocabularyFilter destructiveVocabularyFilter;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    AgentRoleCache roleCache;

    @Inject
    IntentClassificationService intentDetector;

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

        if (destructiveVocabularyFilter.isDestructive(message.text())) {
            log.warnf(
                    "[IntentClassificationHandler] Destructive vocabulary detected "
                            + "- blocking before agent dispatch. chat=%s",
                    context.getChatId());
            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), DESTRUCTIVE_GUARD_REPLY));
            return HandleResult.STOP;
        }

        try {
            String role = classifyRole(context, message);
            context.setAgentRole(role);
            log.infof("Classified message as role '%s' for chat %s", role, context.getChatId());
        } catch (HttpException e) {
            handleHttpException(context, e);
        } catch (Exception e) {
            handleGeneralException(context, e);
        }

        return HandleResult.CONTINUE;
    }

    private String classifyRole(MessageContext context, ChannelMessage message) {
        String sid = context.getSessionId().orElseGet(() -> {
            log.debugf("No sessionId on context for continuity lookup for chat %s - using chatId", context.getChatId());
            return context.getChatId();
        });

        String classificationInput =
                TRAILING_PUNCTUATION_PATTERN.matcher(message.text().trim()).replaceAll("");

        String currentGoal = context.getGoal();
        if (currentGoal != null
                && AgentSentinels.REDACTED_HYPHEN_PATTERN.matcher(currentGoal).find()) {
            log.infof("PII-redacted goal detected for chat %s - forcing coordinator chitchat", context.getChatId());
            context.setData(AgentSentinels.DATA_IS_SIMPLE_CHITCHAT, true);
            context.setData(AgentSentinels.DATA_IS_PII_REDACTED, true);
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        String previousRole = roleCache.findLatestRole(sid).orElse(null);
        boolean isFollowUpHeuristic = intentDetector.isFollowUpHeuristic(classificationInput);

        String role = null;
        if (isFollowUpHeuristic
                && previousRole != null
                && (AgentRoleEnum.CODER.toScopedName().equals(previousRole)
                        || AgentRoleEnum.DEBUGGER.toScopedName().equals(previousRole))) {

            if (intentDetector.isScheduleIntent(classificationInput)) {
                log.infof(
                        "Goal requires scheduling, overriding sticky role %s to coordinator for chat %s",
                        previousRole, context.getChatId());
                role = AgentRoleEnum.COORDINATOR.toScopedName();
            } else {
                log.infof(
                        "Short follow-up detected for chat %s - preserving sticky role: %s",
                        context.getChatId(), previousRole);
                role = previousRole;
            }
        } else {
            role = classifier.classify(classificationInput);
        }

        if ("__CONTINUE__".equals(role)) {
            log.infof("Handling continuity request for session %s - previous role: %s", sid, previousRole);
            role = previousRole != null ? previousRole : AgentRoleEnum.COORDINATOR.toScopedName();
        }

        if (role == null || role.isBlank()) {
            log.warnf(
                    "IntentDetector returned null/blank for chat %s - defaulting to coordinator", context.getChatId());
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        return destructiveVocabularyFilter.filter(role, message.text());
    }

    private void handleHttpException(MessageContext context, HttpException e) {
        if (e.statusCode() == 429 || (e.getMessage() != null && e.getMessage().contains("rate limit"))) {
            log.warnf("Intent classifier rate limited for chat %s - defaulting to coordinator", context.getChatId());
            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), CLASSIFICATION_RATE_LIMITED));
        } else {
            String sanitizedMsg = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            log.errorf(
                    "Intent classification failed (HTTP %d) for chat %s: %s",
                    e.statusCode(), context.getChatId(), sanitizedMsg);
            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), sanitizedMsg));
        }
        context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
    }

    private void handleGeneralException(MessageContext context, Exception e) {
        log.warnf(
                "Intent classification failed for chat %s - defaulting to coordinator: %s",
                context.getChatId(), LogUtil.rootMessage(e));
        context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
    }
}
