package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.function.BiConsumer;

import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.hitl.ApprovalNotifier;
import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.cron.AgentJobCompleted;
import com.amsha.core.domain.model.AgentCron;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.event.FlushQueuedNotifications;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.core.util.AgentResultFormatter;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ApprovalNotificationService implements ApprovalNotifier {

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
            log.warn("Failed to flush notifications", LogUtil.relevantThrowable(e));
        }
    }

    public BiConsumer<AgentCron, AgentResult> createJobNotifier() {
        return (cron, result) -> {
            ChannelType chType = ChannelType.fromString(cron.getChannelType()).orElse(ChannelType.TELEGRAM);
            String platformId = cron.getChannelPlatformId() != null ? cron.getChannelPlatformId() : cron.getChatId();
            String toolName = result instanceof AgentResult.AwaitingApproval aa ? aa.toolName() : null;
            String approvalSessionId = result instanceof AgentResult.AwaitingApproval aa ? aa.sessionId() : null;
            String displayParamsJson =
                    result instanceof AgentResult.AwaitingApproval aa ? aa.displayParamsJson() : null;
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
                            approvalSessionId,
                            toolName,
                            displayParamsJson));
        };
    }
}
