package com.arcclaw.app.channel;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.lifecycle.ApprovalTimedOutEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApprovalTimeoutNotifier {

    private static final Logger log = Logger.getLogger(ApprovalTimeoutNotifier.class);

    @Inject
    ChannelSenderRegistry senders;

    void onApprovalTimedOut(@ObservesAsync ApprovalTimedOutEvent event) {
        try {
            if (event.channelPlatformId() == null || event.channelPlatformId().isBlank()) {
                log.debugf("No channelPlatformId for session=%s — skipping timeout notification",
                        event.sessionId());
                return;
            }

            // Fallback to TELEGRAM for notifications if type missing
            ChannelType channelType = ChannelType.fromString(event.channelType()).orElse(ChannelType.TELEGRAM);
            ChannelUser recipient = new ChannelUser(
                    event.channelPlatformId(), "user", channelType);

            String toolName = event.pendingToolName() != null
                    ? event.pendingToolName() : "unknown";

            String message = switch (channelType) {
                case TELEGRAM -> "⏰ Approval for tool *" + escapeMdV2(toolName)
                        + "* timed out after " + event.timeoutMinutes()
                        + " minutes\\. The pending action was cancelled\\.";
                default -> "⏰ Approval for tool '" + toolName + "' timed out after "
                        + event.timeoutMinutes() + " minutes. The pending action was cancelled.";
            };

            senders.sendRaw(recipient, event.sessionId(), message);
            log.infof("Notified user chatId=%s of approval timeout for session=%s",
                    event.channelPlatformId(), event.sessionId());

        } catch (Exception e) {
            log.warnf("Failed to notify approval timeout for session=%s: %s",
                    event.sessionId(), e.getMessage());
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
