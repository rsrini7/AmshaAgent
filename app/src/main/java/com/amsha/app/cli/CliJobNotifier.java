package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * CLI implementation of {@link ChannelJobNotifier}.
 * Prints cron results and approval prompts to stderr so they don't interfere
 * with the interactive {@code You>} prompt on stdout.
 */
@ApplicationScoped
public class CliJobNotifier implements ChannelJobNotifier {

    private static final Logger log = Logger.getLogger(CliJobNotifier.class);

    @Override
    public boolean supports(ChannelType channelType) {
        return ChannelType.CLI.equals(channelType);
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        try {
            String answer = renderedMessage != null ? renderedMessage : "(no result)";
            System.err.println("\n[Cron Result] " + answer);
            System.err.flush();
            log.infof("CLI-CRON-DELIVERED sessionId=%s", sessionId);
        } catch (Exception e) {
            log.errorf("CLI-CRON-NOTIFY-FAIL sessionId=%s:\n%s", sessionId, LogUtil.trimmedStack(e));
        }
    }

    @Override
    public void notifyApprovalRequired(
            String recipient, String cronSessionId, String toolName, String renderedMessage) {
        try {
            System.err.println("\n[Cron Approval Required] Tool: " + toolName);
            if (renderedMessage != null && !renderedMessage.isBlank()) {
                System.err.println(renderedMessage);
            }
            System.err.println("This approval belongs to a background cron session, not your interactive chat. "
                    + "Reply /approve <id> or /deny <id> with the cron id shown above. "
                    + "A bare /approve only applies to your interactive session.");
            System.err.flush();
            log.infof("CLI-CRON-APPROVAL-NOTIFY session=%s tool=%s", cronSessionId, toolName);
        } catch (Exception e) {
            log.errorf("CLI-CRON-APPROVAL-NOTIFY-FAIL session=%s:\n%s", cronSessionId, LogUtil.trimmedStack(e));
        }
    }

    @Override
    public void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes) {
        try {
            String toolName = pendingToolName != null ? pendingToolName : "unknown";
            System.err.println("\n[Cron Approval Timeout] Tool '" + toolName + "' timed out after " + timeoutMinutes
                    + " minutes. Action cancelled.");
            System.err.flush();
            log.infof("CLI-CRON-TIMEOUT session=%s tool=%s minutes=%d", sessionId, toolName, timeoutMinutes);
        } catch (Exception e) {
            log.warnf("CLI-CRON-TIMEOUT-FAIL session=%s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }
}
