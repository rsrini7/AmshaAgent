package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Consumer;

import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelType;
import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * CLI implementation of {@link ChannelJobNotifier}.
 * Prints cron results and approval prompts to the terminal. When an interactive
 * line reader is active, routes via {@code printAbove} so output appears cleanly
 * above the prompt and the {@code You>} prompt is automatically redrawn.
 */
@ApplicationScoped
public class CliJobNotifier implements ChannelJobNotifier {

    private static final Logger log = Logger.getLogger(CliJobNotifier.class);
    private static volatile Consumer<String> terminalPrinter;

    public static void setTerminalPrinter(Consumer<String> printer) {
        terminalPrinter = printer;
    }

    static void printOutput(String text) {
        Consumer<String> printer = terminalPrinter;
        if (printer != null) {
            printer.accept(text);
        } else {
            System.err.println(text);
            System.err.flush();
        }
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return ChannelType.CLI.equals(channelType);
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        try {
            String answer = renderedMessage != null ? renderedMessage : "(no result)";
            printOutput("\n[Cron Result] " + answer);
            log.infof("CLI-CRON-DELIVERED sessionId=%s", sessionId);
        } catch (Exception e) {
            log.errorf("CLI-CRON-NOTIFY-FAIL sessionId=%s:\n%s", sessionId, LogUtil.trimmedStack(e));
        }
    }

    @Override
    public void notifyApprovalRequired(
            String recipient, String cronSessionId, String toolName, String renderedMessage) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n[Cron Approval Required] Tool: ").append(toolName).append("\n");
            if (renderedMessage != null && !renderedMessage.isBlank()) {
                sb.append(renderedMessage).append("\n");
            }
            if (renderedMessage == null
                    || !renderedMessage.contains("This approval belongs to a background cron session")) {
                sb.append("This approval belongs to a background cron session, not your interactive chat. ")
                        .append("Reply /approve <id> or /deny <id> with the cron id shown above. ")
                        .append("A bare /approve only applies to your interactive session.\n");
            }
            printOutput(sb.toString().stripTrailing());
            log.infof("CLI-CRON-APPROVAL-NOTIFY session=%s tool=%s", cronSessionId, toolName);
        } catch (Exception e) {
            log.errorf("CLI-CRON-APPROVAL-NOTIFY-FAIL session=%s:\n%s", cronSessionId, LogUtil.trimmedStack(e));
        }
    }

    @Override
    public void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes) {
        try {
            String toolName = pendingToolName != null ? pendingToolName : "unknown";
            printOutput("\n[Cron Approval Timeout] Tool '" + toolName + "' timed out after " + timeoutMinutes
                    + " minutes. Action cancelled.");
            log.infof("CLI-CRON-TIMEOUT session=%s tool=%s minutes=%d", sessionId, toolName, timeoutMinutes);
        } catch (Exception e) {
            log.warnf("CLI-CRON-TIMEOUT-FAIL session=%s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }
}
