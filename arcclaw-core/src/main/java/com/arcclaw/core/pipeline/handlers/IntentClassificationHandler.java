package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.IntentClassifier;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Classifies free-text messages to determine the agent role.
 */
@ApplicationScoped
public class IntentClassificationHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(IntentClassificationHandler.class);

    @Inject
    IntentClassifier classifier;

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        if (context.getAgentRole().isPresent()) {
            return HandleResult.CONTINUE; // Already resolved
        }

        ChannelMessage message = context.getMessage();
        if (message != null && message.text() != null) {
            try {
                String role = classifier.classify(message.text());
                context.setAgentRole(role);
                log.infof("Classified message as role '%s' for chat %s", role, context.getChatId());
            } catch (Exception e) {
                log.warnf("Intent classification failed, defaulting to 'coordinator': %s", e.getMessage());
                context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
            }
        } else {
            // Default for non-text messages if needed, though they usually shouldn't reach here without a role
            context.setAgentRole(AgentRoleEnum.COORDINATOR.toScopedName());
        }

        return HandleResult.CONTINUE;
    }
}
