package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.BiConsumer;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.domain.model.AgentCron;
import com.arcclaw.core.event.DomainEventBus;
import com.arcclaw.core.event.EventTypeCatalog;
import com.arcclaw.core.event.FlushQueuedNotifications;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.util.AgentResultFormatter;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ApprovalNotificationService {

    @Inject
    Logger log;

    @Inject
    StatefulAgentStore statefulAgentStore;

    @Inject
    DomainEventBus eventBus;

    public void flushPendingNotifications(String chatId) {
        try {
            if (!statefulAgentStore.hasPendingApprovalForChat(chatId)) {
                eventBus.publish(EventTypeCatalog.FLUSH_QUEUED_NOTIFICATIONS, new FlushQueuedNotifications(chatId));
            }
        } catch (Exception e) {
            log.warn("Failed to flush notifications", e);
        }
    }

    public BiConsumer<AgentCron, AgentResult> createJobNotifier() {
        return (cron, result) -> {
            ChannelType chType = ChannelType.fromString(cron.getChannelType()).orElse(ChannelType.TELEGRAM);
            String platformId = cron.getChannelPlatformId() != null ? cron.getChannelPlatformId() : cron.getChatId();
            String toolName = result instanceof AgentResult.AwaitingApproval aa ? aa.toolName() : null;
            eventBus.publish(
                    EventTypeCatalog.AGENT_JOB_COMPLETED,
                    new AgentJobCompleted(
                            cron.getId(),
                            cron.getUserId(),
                            chType,
                            platformId,
                            cron.getLastDeliveryId(),
                            AgentResultFormatter.format(result),
                            result instanceof AgentResult.Failed f ? f.category() : null,
                            null,
                            toolName));
        };
    }
}
