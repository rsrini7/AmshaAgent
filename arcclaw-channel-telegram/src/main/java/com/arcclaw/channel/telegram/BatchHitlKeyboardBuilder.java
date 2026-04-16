package com.arcclaw.channel.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.camel.component.telegram.model.InlineKeyboardButton;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.jboss.logging.Logger;

/**
 * Builds the Telegram {@link InlineKeyboardMarkup} for a parallel batch HITL pause.
 * §7.24 §7.25
 */
class BatchHitlKeyboardBuilder {

    private static final Logger log = Logger.getLogger(BatchHitlKeyboardBuilder.class);

    /**
     * @param sessionId the active session ID (embedded in callback data)
     * @param toolNames ordered list of tool names in the batch
     * @return InlineKeyboardMarkup for Telegram
     */
    static InlineKeyboardMarkup buildKeyboard(String sessionId, List<String> toolNames) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Format sessionId to prevent collision with pipe separator (§7.25)
        String safeSessionId = sessionId.replace("|", "_").replace(":", "_");

        for (String toolName : toolNames) {
            String safeToolName = toolName.replace("|", "_").replace(":", "_");
            String prefix = safeSessionId.length() > 8 ? safeSessionId.substring(0, 8) : safeSessionId;
            String approveCb = truncateCb("BATCH_APPROVE|" + prefix + "|" + safeToolName);
            String denyCb = truncateCb("BATCH_DENY|" + prefix + "|" + safeToolName);
            
            List<InlineKeyboardButton> row = new ArrayList<>();
            
            InlineKeyboardButton appBtn = new InlineKeyboardButton();
            appBtn.setText("✅ " + toolName);
            appBtn.setCallbackData(approveCb);
            row.add(appBtn);
            
            InlineKeyboardButton denBtn = new InlineKeyboardButton();
            denBtn.setText("🚫 " + toolName);
            denBtn.setCallbackData(denyCb);
            row.add(denBtn);
            
            rows.add(row);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setInlineKeyboard(rows);
        return markup;
    }

    private static String truncateCb(String cbInfo) {
        if (cbInfo.length() > 64) {
            log.warnf("Callback data exceeded 64 bytes and will be truncated: %s", cbInfo);
            return cbInfo.substring(0, 64);
        }
        return cbInfo;
    }

    /**
     * Parses a callback_data string back into a BatchCallbackEvent.
     * Returns empty Optional if format is unrecognized.
     */
    static Optional<BatchCallbackEvent> parseCallback(String callbackData) {
        if (callbackData == null || !callbackData.startsWith("BATCH_")) {
            return Optional.empty();
        }

        // Switch to pipe separator to support namespaced tools (§7.25)
        String[] parts = callbackData.split("\\|");
        if (parts.length < 2) return Optional.empty();

        String action = parts[0];
        String sessionId = parts[1];

        return switch (action) {
            case "BATCH_APPROVE" -> {
                if (parts.length < 3) yield Optional.empty();
                yield Optional.of(new BatchCallbackEvent.ApproveTool(sessionId, parts[2]));
            }
            case "BATCH_DENY" -> {
                if (parts.length < 3) yield Optional.empty();
                yield Optional.of(new BatchCallbackEvent.DenyTool(sessionId, parts[2]));
            }
            default -> Optional.empty();
        };
    }

    sealed interface BatchCallbackEvent {
        record ApproveTool(String sessionId, String toolName) implements BatchCallbackEvent {}
        record DenyTool(String sessionId, String toolName) implements BatchCallbackEvent {}
    }
}
