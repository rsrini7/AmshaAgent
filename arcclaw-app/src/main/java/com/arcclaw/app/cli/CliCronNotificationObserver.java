package com.arcclaw.app.cli;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.cron.AgentJobCompleted;
import com.arcclaw.core.util.LogUtil;
import org.jboss.logging.Logger;

/**
 * Delivers cron job results to stdout for CLI-scheduled tasks.
 * Fires when a cron job was scheduled from a CLI session.
 */
@ApplicationScoped
public class CliCronNotificationObserver {

    private static final Logger log = Logger.getLogger(CliCronNotificationObserver.class);

    void onJobCompleted(@Observes AgentJobCompleted event) {
        if (event.channelType() != ChannelType.CLI) {
            return;
        }

        try {
            String answer = event.renderedAnswer();
            if (answer == null || answer.isBlank()) return;

            // Print inline to the active CLI session
            System.out.println("\n[⏰ Scheduled Task] " + answer);
            System.out.print("\nYou> "); // re-print prompt so input line isn't lost
        } catch (Exception e) {
            log.errorf("CLI-CRON-NOTIFY-FAIL cronId=%s:\n%s", event.cronId(), LogUtil.trimmedStack(e));
        }
    }
}
