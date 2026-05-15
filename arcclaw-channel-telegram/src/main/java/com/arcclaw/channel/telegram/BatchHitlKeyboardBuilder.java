package com.arcclaw.channel.telegram;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.camel.component.telegram.model.InlineKeyboardButton;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.jboss.logging.Logger;

/**
 * Builds the Telegram {@link InlineKeyboardMarkup} for a parallel batch HITL pause.
 * §7.24 §7.25
 */
class BatchHitlKeyboardBuilder {

    private static final Logger log = Logger.getLogger(BatchHitlKeyboardBuilder.class);
    private static final Pattern PIPE_PATTERN = Pattern.compile("\\|");
    private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();
    private static final Set<String> KNOWN_PREFIXES = Set.of("cron-");

    /**
     * @param sessionId the active session ID (embedded in callback data)
     * @param toolNames ordered list of tool names in the batch
     * @return InlineKeyboardMarkup for Telegram
     */
    static InlineKeyboardMarkup buildKeyboard(String sessionId, List<String> toolNames) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Format sessionId to prevent collision with pipe separator (§7.25)
        String safeSessionId = sessionId.replace("|", "_").replace(":", "_");
        String compressedId = compressSessionId(safeSessionId);

        for (String toolName : toolNames) {
            String safeToolName = toolName.replace("|", "_").replace(":", "_");
            String approveCb = truncateCb("BATCH_APPROVE|" + compressedId + "|" + safeToolName);
            String denyCb = truncateCb("BATCH_DENY|" + compressedId + "|" + safeToolName);
            
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

        // Add Approve All / Deny All for batches
        if (toolNames.size() > 1) {
            List<InlineKeyboardButton> lastRow = new ArrayList<>();

            InlineKeyboardButton appAll = new InlineKeyboardButton();
            appAll.setText("✅ Approve All");
            appAll.setCallbackData(truncateCb("BATCH_APPROVE|" + compressedId + "|all"));
            lastRow.add(appAll);

            InlineKeyboardButton denAll = new InlineKeyboardButton();
            denAll.setText("🚫 Deny All");
            denAll.setCallbackData(truncateCb("BATCH_DENY|" + compressedId + "|all"));
            lastRow.add(denAll);

            rows.add(lastRow);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setInlineKeyboard(rows);
        return markup;
    }

    /**
     * Compresses a session ID by base64-encoding any UUID portion (36 chars → 22 chars)
     * to fit within Telegram's 64-byte callback data limit.
     * Handles plain UUIDs, and UUIDs prefixed by known prefixes like "cron-".
     */
    static String compressSessionId(String raw) {
        if (raw == null) return null;
        String prefix = "";
        String body = raw;
        for (String p : KNOWN_PREFIXES) {
            if (raw.startsWith(p)) {
                prefix = p;
                body = raw.substring(p.length());
                break;
            }
        }
        if (body.length() >= 36 && isUuid(body.substring(0, 36))) {
            String uuidStr = body.substring(0, 36);
            String suffix = body.substring(36);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                byte[] bytes = ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();
                return prefix + B64_ENCODER.encodeToString(bytes) + suffix;
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * Reverses {@link #compressSessionId(String)} to recover the original session ID.
     */
    static String decompressSessionId(String compressed) {
        if (compressed == null) return null;
        String prefix = "";
        String body = compressed;
        for (String p : KNOWN_PREFIXES) {
            if (compressed.startsWith(p)) {
                prefix = p;
                body = compressed.substring(p.length());
                break;
            }
        }
        if (body.length() >= 22) {
            String b64 = body.substring(0, 22);
            String suffix = body.substring(22);
            try {
                byte[] bytes = B64_DECODER.decode(b64);
                ByteBuffer bb = ByteBuffer.wrap(bytes);
                UUID uuid = new UUID(bb.getLong(), bb.getLong());
                return prefix + uuid.toString() + suffix;
            } catch (Exception e) {
                return compressed;
            }
        }
        return compressed;
    }

    static boolean isUuid(String s) {
        return s.length() == 36
            && s.charAt(8) == '-' && s.charAt(13) == '-'
            && s.charAt(18) == '-' && s.charAt(23) == '-';
    }

    /**
     * Structure-aware truncation: preserves the {@code action|sessionId|} prefix
     * and only truncates the tool name portion to fit within 64 bytes.
     */
    private static String truncateCb(String cbInfo) {
        if (cbInfo.length() <= 64) return cbInfo;
        log.warnf("Callback data exceeded 64 bytes and will be truncated: %s", cbInfo);
        int firstPipe = cbInfo.indexOf('|');
        if (firstPipe > 0) {
            int secondPipe = cbInfo.indexOf('|', firstPipe + 1);
            if (secondPipe > 0) {
                String prefix = cbInfo.substring(0, secondPipe + 1);
                int remaining = 64 - prefix.length();
                if (remaining > 0) {
                    String tool = cbInfo.substring(secondPipe + 1);
                    return prefix + tool.substring(0, Math.min(remaining, tool.length()));
                }
            }
        }
        return cbInfo.substring(0, 64);
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
        String[] parts = PIPE_PATTERN.split(callbackData);
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
