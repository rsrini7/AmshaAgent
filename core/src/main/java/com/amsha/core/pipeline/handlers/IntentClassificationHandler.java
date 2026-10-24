package com.amsha.core.pipeline.handlers;

import static com.amsha.core.message.UserMessages.*;

import dev.langchain4j.exception.HttpException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Pattern;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentRoleCache;
import com.amsha.core.agent.loop.TestDataCollectionService;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.api.AgentSessionService;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessageHandler;
import com.amsha.core.routing.SkillAwareIntentDetector;
import com.amsha.core.security.DestructiveVocabularyFilter;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.tool.api.AgentSentinels;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Classifies free-text messages to determine the agent role.
 */
@ApplicationScoped
public class IntentClassificationHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(IntentClassificationHandler.class);

    private static final Pattern TRAILING_PUNCTUATION_PATTERN = Pattern.compile("[/?\\s]+$");

    @Inject
    SkillAwareIntentDetector classifier;

    @Inject
    DestructiveVocabularyFilter destructiveVocabularyFilter;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    AgentRoleCache roleCache;

    @Inject
    IntentClassificationService intentDetector;

    @Inject
    AgentSessionService sessionService;

    @Inject
    TestDataCollectionService testDataCollectionService;

    @ConfigProperty(name = "amsha.test-data-collection.enabled", defaultValue = "false")
    boolean testDataCollectionEnabled;

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        ChannelMessage message = context.getMessage();
        if (message == null || message.text() == null || message.text().isBlank()) {
            if (context.getAgentRole().isEmpty()) {
                context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
            }
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

        // Always check for skill matches — overrides any pre-set role (e.g. from REST API)
        classifier.findSkillMatch(message.text()).ifPresent(skillRole -> {
            String previous = context.getAgentRole().orElse("none");
            log.infof(
                    "[IntentClassificationHandler] Skill %s matched, overriding role from '%s'.", skillRole, previous);
            context.setAgentRole(skillRole);
        });

        // If role already set (and no skill override), skip LLM classification
        if (context.getAgentRole().isPresent()) {
            recordIntentCandidate(context, message.text());
            return HandleResult.CONTINUE;
        }

        try {
            String role = classifyRole(context, message);
            context.setAgentRole(role);
            recordIntentCandidate(context, message.text());
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

        if (intentDetector.isSelfEvolveIntent(classificationInput)) {
            log.infof(
                    "Self-evolve or directive-status request detected for chat %s - routing to coordinator",
                    context.getChatId());
            return AgentRoleEnum.COORDINATOR.toScopedName();
        }

        String previousRole = roleCache.findLatestRole(sid).orElse(null);
        boolean isFollowUpHeuristic = intentDetector.isFollowUpHeuristic(classificationInput);

        String role;
        if (isFollowUpHeuristic && previousRole != null) {
            if (intentDetector.isScheduleIntent(classificationInput)) {
                log.infof(
                        "Goal requires scheduling, overriding sticky role %s to coordinator for chat %s",
                        previousRole, context.getChatId());
                role = AgentRoleEnum.COORDINATOR.toScopedName();
            } else if (AgentRoleEnum.CODER.toScopedName().equals(previousRole)
                    || AgentRoleEnum.DEBUGGER.toScopedName().equals(previousRole)) {
                log.infof(
                        "Short follow-up detected for chat %s - preserving sticky role: %s",
                        context.getChatId(), previousRole);
                role = previousRole;
            } else if (AgentRoleEnum.COORDINATOR.toScopedName().equals(previousRole)
                    && intentDetector.isExecutionFollowUp(classificationInput)) {
                log.infof("Execution follow-up from coordinator for chat %s - routing to coder", context.getChatId());
                role = AgentRoleEnum.CODER.toScopedName();
            } else {
                role = classifier.classify(classificationInput);
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

    private void recordIntentCandidate(MessageContext context, String message) {
        if (!testDataCollectionEnabled || sessionService == null || testDataCollectionService == null) {
            return;
        }
        try {
            String sessionId = context.getSessionId().orElse(null);
            String expectedLabel = sessionService.getExpectedTestLabel(sessionId, "intent");
            if (expectedLabel != null) {
                testDataCollectionService.recordIntentCandidate(expectedLabel, message);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Test-data collection must never alter authoritative routing.
        }
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
