package com.arcclaw.channel.telegram;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

/**
 * Builds the Telegram inline_keyboard JSON for a parallel batch HITL pause.
 * §7.24 §7.25
 */
class BatchHitlKeyboardBuilder {

    private static final Logger log = Logger.getLogger(BatchHitlKeyboardBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param sessionId the active session ID (embedded in callback data)
     * @param toolNames ordered list of tool names in the batch
     * @return inline_keyboard JSON string for Telegram
     */
    static String buildKeyboardJson(String sessionId, List<String> toolNames) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode keyboard = root.putArray("inline_keyboard");

        // Format sessionId
        String safeSessionId = sessionId.replace(":", "_");

        for (String toolName : toolNames) {
            String safeToolName = toolName.replace(":", "_");
            String approveCb = truncateCb("BATCH_APPROVE:" + safeSessionId + ":" + safeToolName);
            String denyCb = truncateCb("BATCH_DENY:" + safeSessionId + ":" + safeToolName);
            
            ArrayNode row = MAPPER.createArrayNode();
            
            ObjectNode appBtn = MAPPER.createObjectNode();
            appBtn.put("text", "✅ " + toolName);
            appBtn.put("callback_data", approveCb);
            row.add(appBtn);
            
            ObjectNode denBtn = MAPPER.createObjectNode();
            denBtn.put("text", "🚫 " + toolName);
            denBtn.put("callback_data", denyCb);
            row.add(denBtn);
            
            keyboard.add(row);
        }

        // Final row
        ArrayNode finalRow = MAPPER.createArrayNode();
        
        ObjectNode approveAllBtn = MAPPER.createObjectNode();
        approveAllBtn.put("text", "✅ Approve All");
        approveAllBtn.put("callback_data", truncateCb("BATCH_APPROVE_ALL:" + safeSessionId));
        finalRow.add(approveAllBtn);

        ObjectNode denyAllBtn = MAPPER.createObjectNode();
        denyAllBtn.put("text", "🚫 Deny All");
        denyAllBtn.put("callback_data", truncateCb("BATCH_DENY_ALL:" + safeSessionId));
        finalRow.add(denyAllBtn);

        keyboard.add(finalRow);

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Failed to generate keyboard JSON", e);
            return null;
        }
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

        String[] parts = callbackData.split(":");
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
            case "BATCH_APPROVE_ALL" -> Optional.of(new BatchCallbackEvent.ApproveAll(sessionId));
            case "BATCH_DENY_ALL" -> Optional.of(new BatchCallbackEvent.DenyAll(sessionId));
            default -> Optional.empty();
        };
    }

    sealed interface BatchCallbackEvent {
        record ApproveTool(String sessionId, String toolName) implements BatchCallbackEvent {}
        record DenyTool(String sessionId, String toolName) implements BatchCallbackEvent {}
        record ApproveAll(String sessionId) implements BatchCallbackEvent {}
        record DenyAll(String sessionId) implements BatchCallbackEvent {}
    }
}
