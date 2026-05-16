package com.arcclaw.app.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.cron.AgentJobCompleted;
import org.jboss.logging.Logger;

/**
 * Dispatcher that routes cron job completion events to the correct ChannelJobNotifier.
 * <p>
 * Replaces the hardcoded TelegramCronNotificationObserver.
 */
@ApplicationScoped
public class ChannelCronNotificationDispatcher {

    private static final Logger log = Logger.getLogger(ChannelCronNotificationDispatcher.class);

    @Inject
    ChannelPluginRegistry pluginRegistry;

    void onJobCompleted(@Observes AgentJobCompleted event) {
        if (event.channelType() == null) {
            log.debugf("CRON-NOTIFY skipped for missing channelType cronId=%s", event.cronId());
            return;
        }

        if (event.failureCategory() != null) {
            log.warnf("CRON-FAIL cronId=%s category=%s", event.cronId(), event.failureCategory());
        }

        pluginRegistry.find(event.channelType())
            .map(ChannelPlugin::jobNotifier)
            .ifPresentOrElse(
                n -> {
                    if (event.toolName() != null) {
                        n.notifyApprovalRequired(event.channelPlatformId(), event.cronSessionId(), event.toolName(), event.renderedAnswer());
                    } else {
                        n.notifyJobCompleted(event.channelPlatformId(), event.renderedAnswer(), event.correlationId());
                    }
                },
                () -> {
                    if (ChannelType.CLI.id().equalsIgnoreCase(event.channelType().id())) {
                        log.warnf("CRON-UNDELIVERABLE cronId=%s channel=cli - CLI interactive session has no background result receiver. Task completed but not printed to stderr/stdout.", event.cronId());
                        log.warnf("[ArcClaw] Background task completed: %s", event.renderedAnswer());
                    } else {
                        log.warnf("No ChannelPlugin for channelType [%s] - dropping notification", event.channelType());
                    }
                }
            );
    }
}
