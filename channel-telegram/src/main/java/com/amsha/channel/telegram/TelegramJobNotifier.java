package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Pattern;

import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.common.util.LogUtil;

import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.jboss.logging.Logger;

/**
 * Telegram implementation of {@link ChannelJobNotifier}.
 * Sends notifications to Telegram users with inline keyboard support for approvals.
 */
@ApplicationScoped
public class TelegramJobNotifier implements ChannelJobNotifier {

    private static final Logger log = Logger.getLogger(TelegramJobNotifier.class);
    private static final Pattern RECIPIENT_ID_PATTERN = Pattern.compile("-?\\d+");

    @Inject
    TelegramChannelSender sender;

    @Inject
    TelegramMarkdownV2Renderer markdownRenderer;

    @Override
    public boolean supports(ChannelType channelType) {
        return ChannelType.TELEGRAM.equals(channelType);
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        if (recipient == null || !RECIPIENT_ID_PATTERN.matcher(recipient).matches()) {
            log.warnf("CRON-NOTIFY skipped - chatId '%s' is not a valid Telegram ID", recipient);
            return;
        }

        try {
            ChannelUser user = new ChannelUser(recipient, recipient, "user", ChannelType.TELEGRAM);
            sender.sendRaw(user, renderedMessage);
        } catch (Exception e) {
            log.errorf(
                    "CRON-NOTIFY-FAIL recipient=%s sessionId=%s:\n%s", recipient, sessionId, LogUtil.trimmedStack(e));
        }
    }

    @Override
    public void notifyApprovalRequired(
            String recipient, String cronSessionId, String toolName, String renderedMessage) {
        if (recipient == null || !RECIPIENT_ID_PATTERN.matcher(recipient).matches()) {
            log.warnf("CRON-APPROVAL-NOTIFY skipped - chatId '%s' is not a valid Telegram ID", recipient);
            return;
        }

        try {
            ChannelUser user = new ChannelUser(recipient, recipient, "user", ChannelType.TELEGRAM);
            InlineKeyboardMarkup markup = BatchHitlKeyboardBuilder.buildKeyboard(cronSessionId, List.of(toolName));
            sender.sendWithKeyboard(user, escapeMdV2(renderedMessage), markup);
            log.infof("CRON-APPROVAL-NOTIFY sent to=%s session=%s tool=%s", recipient, cronSessionId, toolName);
        } catch (Exception e) {
            log.errorf(
                    "CRON-APPROVAL-NOTIFY-FAIL recipient=%s session=%s:\n%s",
                    recipient, cronSessionId, LogUtil.trimmedStack(e));
        }
    }

    @Override
    public void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes) {
        if (recipient == null || recipient.isBlank()) {
            log.debugf("No recipient for session=%s - skipping timeout notification", sessionId);
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
            log.warnf("Failed to notify approval timeout for session=%s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    private String escapeMdV2(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        return markdownRenderer.esc(text);
    }
}
