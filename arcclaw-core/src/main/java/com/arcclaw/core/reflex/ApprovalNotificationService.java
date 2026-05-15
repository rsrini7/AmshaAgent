package com.arcclaw.core.reflex;

import java.util.function.BiConsumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.domain.model.AgentCron;
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
    Event<AgentJobCompleted> jobCompletedEvent;

    @Inject
    Event<FlushQueuedNotifications> flushEvent;

    public void flushPendingNotifications(String chatId) {
        try {
            if (flushEvent != null && !statefulAgentStore.hasPendingApprovalForChat(chatId)) {
                flushEvent.fire(new FlushQueuedNotifications(chatId));
            }
        } catch (Exception e) {
            log.warn("Failed to flush notifications", e);
        }
    }

    public BiConsumer<AgentCron, AgentResult> createJobNotifier() {
        return (cron, result) -> {
            ChannelType chType = ChannelType.fromString(cron.getChannelType()).orElse(ChannelType.TELEGRAM);
            String platformId = cron.getChannelPlatformId() != null ? cron.getChannelPlatformId() : cron.getChatId();
            jobCompletedEvent.fire(new AgentJobCompleted(
                cron.getId(), cron.getUserId(), chType, platformId, cron.getLastDeliveryId(),
                AgentResultFormatter.format(result),
                result instanceof AgentResult.Failed f ? f.category() : null));
        };
    }
}
