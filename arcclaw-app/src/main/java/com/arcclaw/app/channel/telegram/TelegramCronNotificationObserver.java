package com.arcclaw.app.channel.telegram;

import com.arcclaw.app.channel.ChannelSenderRegistry;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.cron.AgentJobCompleted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * CDI observer that delivers cron job results to Telegram users.
 * <p>
 * Failure isolation: the entire observer body is wrapped in try/catch
 * because CDI {@code @Observes} is synchronous — a Telegram timeout or
 * rate-limit exception would propagate back to the scheduler thread and
 * could roll back the surrounding transaction (markCompleted/markFired).
 */
@ApplicationScoped
public class TelegramCronNotificationObserver {

    private static final Logger log = Logger.getLogger(TelegramCronNotificationObserver.class);

    @Inject
    ChannelSenderRegistry senders;

    void onJobCompleted(@Observes AgentJobCompleted event) {
        ChannelType type = event.channelType() != null
                ? event.channelType() : ChannelType.TELEGRAM;

        if (type != ChannelType.TELEGRAM) {
            log.warnf("Unhandled channel type '%s' for cronId=%s — skipping delivery",
                    type, event.cronId());
            return;
        }

        if (event.failureCategory() != null) {
            log.warnf("CRON-FAIL cronId=%s category=%s", event.cronId(), event.failureCategory());
        }

        try {
            ChannelUser recipient = new ChannelUser(
                    event.channelPlatformId(), event.userId(), ChannelType.TELEGRAM);
            senders.sendRaw(recipient, event.correlationId(), event.renderedAnswer());
        } catch (Exception e) {
            // Failure isolation: observer exceptions must NOT propagate back
            // to the scheduler thread or roll back the markCompleted/markFired TX
            log.errorf(e, "CRON-NOTIFY-FAIL cronId=%s correlationId=%s",
                    event.cronId(), event.correlationId());
        }
    }
}
