package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelJobNotifier;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import org.jboss.logging.Logger;

/**
 * Telegram implementation of {@link ChannelJobNotifier}.
 * Sends notifications to Telegram users.
 */
@ApplicationScoped
public class TelegramJobNotifier implements ChannelJobNotifier {

    private static final Logger log = Logger.getLogger(TelegramJobNotifier.class);

    @Inject
    TelegramChannelSender sender;

    @Override
    public boolean supports(ChannelType channelType) {
        return ChannelType.TELEGRAM.equals(channelType);
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        if (recipient == null || !recipient.matches("-?\\d+")) {
            log.warnf("CRON-NOTIFY skipped — chatId '%s' is not a valid Telegram ID", recipient);
            return;
        }

        try {
            ChannelUser user = new ChannelUser(recipient, recipient, "user", ChannelType.TELEGRAM);
            sender.sendRaw(user, renderedMessage);
        } catch (Exception e) {
            log.errorf(e, "CRON-NOTIFY-FAIL recipient=%s sessionId=%s", recipient, sessionId);
        }
    }

    @Override
    public void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes) {
        if (recipient == null || recipient.isBlank()) {
            log.debugf("No recipient for session=%s — skipping timeout notification", sessionId);
            return;
        }

        try {
            ChannelUser user = new ChannelUser(recipient, recipient, "user", ChannelType.TELEGRAM);
            String toolName = pendingToolName != null ? pendingToolName : "unknown";
            String message = "⏰ Approval for tool *" + escapeMdV2(toolName)
                    + "* timed out after " + timeoutMinutes
                    + " minutes\\. The pending action was cancelled\\.";

            sender.sendRaw(user, message);
            log.infof("Notified user chatId=%s of approval timeout for session=%s", recipient, sessionId);
        } catch (Exception e) {
            log.warnf("Failed to notify approval timeout for session=%s: %s", sessionId, e.getMessage());
        }
    }

    private String escapeMdV2(String text) {
        if (text == null) return "";
        return text
                .replace("_", "\\_").replace("*", "\\*").replace("[", "\\[")
                .replace("]", "\\]").replace("(", "\\(").replace(")", "\\)")
                .replace("~", "\\~").replace("`", "\\`").replace(">", "\\>")
                .replace("#", "\\#").replace("+", "\\+").replace("-", "\\-")
                .replace("=", "\\=").replace("|", "\\|").replace("{", "\\{")
                .replace("}", "\\}").replace(".", "\\.").replace("!", "\\!");
    }
}
