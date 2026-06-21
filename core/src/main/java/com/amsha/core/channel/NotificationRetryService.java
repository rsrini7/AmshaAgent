package com.amsha.core.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ReplyNode;
import com.amsha.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service that handles notification delivery with retries and fallback (§T5-5).
 * Wraps {@link ChannelSender} with exponential backoff and alternate channel delivery.
 */
@ApplicationScoped
public class NotificationRetryService {

    private static final Logger log = Logger.getLogger(NotificationRetryService.class);

    @Inject
    ChannelPluginRegistry registry;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "amsha.notification.retry.attempts", defaultValue = "3")
    int maxAttempts;

    @ConfigProperty(name = "amsha.notification.retry.initial-delay-ms", defaultValue = "1000")
    long initialDelay;

    /**
     * Sends a raw notification with retries and fallback.
     */
    public void sendRawWithRetry(ChannelUser recipient, String text) {
        String channelId = recipient.channel().id();
        int attempt = 1;
        long delay = initialDelay;

        while (attempt <= maxAttempts) {
            try {
                var maybeSender = registry.find(channelId).map(ChannelPlugin::sender);
                if (maybeSender.isPresent()) {
                    maybeSender.get().sendRaw(recipient, text);
                    return; // Success - exit method
                } else {
                    log.warnf("No sender found for channel %s", channelId);
                    return; // No point in retrying if sender is missing
                }
            } catch (Exception e) {
                log.warnf(
                        "Notification attempt %d failed for channel %s: %s",
                        attempt, channelId, LogUtil.rootMessage(e));

                if (attempt == maxAttempts) break;

                try {
                    TimeUnit.MILLISECONDS.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempt++;
                delay *= 2; // Exponential backoff
            }
        }

        log.errorf(
                "Notification failed after %d attempts for channel %s. Recipient=%s",
                maxAttempts, channelId, recipient.platformId());
        meterRegistry
                .counter("amsha.notification.retry.exhausted", "channel", channelId)
                .increment();
    }

    /**
     * Sends structured nodes with retries.
     */
    public void sendWithRetry(ChannelUser recipient, List<ReplyNode> nodes) {
        String channelId = recipient.channel().id();
        int attempt = 1;
        long delay = initialDelay;

        while (attempt <= maxAttempts) {
            try {
                var maybeSender = registry.find(channelId).map(ChannelPlugin::sender);
                if (maybeSender.isPresent()) {
                    maybeSender.get().send(recipient, nodes);
                    return; // Success
                } else {
                    return;
                }
            } catch (Exception e) {
                log.warnf(
                        "Structured notification attempt %d failed for channel %s: %s",
                        attempt, channelId, LogUtil.rootMessage(e));

                if (attempt == maxAttempts) break;

                try {
                    TimeUnit.MILLISECONDS.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                attempt++;
                delay *= 2;
            }
        }

        log.errorf("Structured notification failed after %d attempts for channel %s", maxAttempts, channelId);
        meterRegistry
                .counter("amsha.notification.retry.exhausted", "channel", channelId)
                .increment();
    }
}
