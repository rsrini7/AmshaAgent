package com.arcclaw.app.channel;

import com.arcclaw.channel.api.ChannelJobNotifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

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
    @Any
    Instance<ChannelJobNotifier> notifiers;

    void onJobCompleted(@Observes AgentJobCompleted event) {
        if (event.channelType() == null) {
            log.debugf("CRON-NOTIFY skipped for missing channelType cronId=%s", event.cronId());
            return;
        }

        if (event.failureCategory() != null) {
            log.warnf("CRON-FAIL cronId=%s category=%s", event.cronId(), event.failureCategory());
        }

        notifiers.stream()
            .filter(n -> n.supports(event.channelType()))
            .findFirst()
            .ifPresentOrElse(
                n -> n.notifyJobCompleted(event.channelPlatformId(), event.renderedAnswer(), event.correlationId()),
                () -> log.warnf("No ChannelJobNotifier found for type [%s]", event.channelType())
            );
    }
}
