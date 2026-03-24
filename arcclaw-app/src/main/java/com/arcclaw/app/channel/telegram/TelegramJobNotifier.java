package com.arcclaw.app.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.app.channel.ChannelJobNotifier;
import com.arcclaw.app.channel.ChannelSenderRegistry;
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
    ChannelSenderRegistry senders;

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.TELEGRAM;
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        if (recipient == null || !recipient.matches("-?\\d+")) {
            log.warnf("CRON-NOTIFY skipped — chatId '%s' is not a valid Telegram ID", recipient);
            return;
        }

        try {
            ChannelUser user = new ChannelUser(recipient, recipient, "user", ChannelType.TELEGRAM);
            senders.sendRaw(user, sessionId, renderedMessage);
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

            senders.sendRaw(user, sessionId, message);
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
