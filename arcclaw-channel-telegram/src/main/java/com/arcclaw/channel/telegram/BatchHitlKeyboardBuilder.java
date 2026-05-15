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
    private static final String COMPRESSED_CRON_PREFIX = "c-";

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
            String approveCb = truncateCb("A|" + compressedId + "|" + safeToolName);
            String denyCb = truncateCb("D|" + compressedId + "|" + safeToolName);
            
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
            appAll.setCallbackData(truncateCb("A|" + compressedId + "|all"));
            lastRow.add(appAll);

            InlineKeyboardButton denAll = new InlineKeyboardButton();
            denAll.setText("🚫 Deny All");
            denAll.setCallbackData(truncateCb("D|" + compressedId + "|all"));
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
     * For cron session IDs (e.g. "cron-{uuid1}-{uuid2}"), both UUIDs are compressed
     * and the prefix is shortened to "c-" to keep callback data under 64 bytes.
     */
    static String compressSessionId(String raw) {
        if (raw == null) return null;
        // Check for cron prefix first — use short prefix, compress all UUIDs
        if (raw.startsWith("cron-")) {
            String body = raw.substring(5);
            if (body.length() >= 36 && isUuid(body.substring(0, 36))) {
                UUID uuid1;
                byte[] bytes1;
                try {
                    uuid1 = UUID.fromString(body.substring(0, 36));
                    bytes1 = ByteBuffer.allocate(16)
                        .putLong(uuid1.getMostSignificantBits())
                        .putLong(uuid1.getLeastSignificantBits())
                        .array();
                } catch (IllegalArgumentException e) {
                    return raw;
                }
                String suffix = body.substring(36);
                // Double UUID: "{uuid1}-{uuid2}"
                if (suffix.startsWith("-") && suffix.length() == 37 && isUuid(suffix.substring(1))) {
                    try {
                        UUID uuid2 = UUID.fromString(suffix.substring(1));
                        byte[] bytes2 = ByteBuffer.allocate(16)
                            .putLong(uuid2.getMostSignificantBits())
                            .putLong(uuid2.getLeastSignificantBits())
                            .array();
                        return COMPRESSED_CRON_PREFIX
                            + B64_ENCODER.encodeToString(bytes1)
                            + B64_ENCODER.encodeToString(bytes2);
                    } catch (IllegalArgumentException e) {
                        return raw;
                    }
                }
                // Single UUID with optional non-UUID suffix
                return COMPRESSED_CRON_PREFIX + B64_ENCODER.encodeToString(bytes1);
            }
        }
        // Plain session ID (single UUID) — standard compression
        if (raw.length() >= 36 && isUuid(raw.substring(0, 36))) {
            String uuidStr = raw.substring(0, 36);
            String suffix = raw.substring(36);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                byte[] bytes = ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();
                return B64_ENCODER.encodeToString(bytes) + suffix;
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * Reverses {@link #compressSessionId(String)} to recover the original session ID.
     * Handles three formats:
     * <ol>
     *   <li>{@code "c-"} prefix — compressed cron: base64(uuid1)+base64(uuid2)
     *   <li>{@code "cron-"} prefix — legacy compressed cron: base64(uuid1)+suffix
     *   <li>No prefix — plain compressed UUID: base64(uuid)
     * </ol>
     */
    static String decompressSessionId(String compressed) {
        if (compressed == null) return null;
        // New format: "c-" + base64(uuid1)[ + base64(uuid2)] — single or double compression
        if (compressed.startsWith(COMPRESSED_CRON_PREFIX)) {
            String body = compressed.substring(2);
            if (body.length() >= 44) {
                String b641 = body.substring(0, 22);
                String b642 = body.substring(22, 44);
                try {
                    ByteBuffer bb1 = ByteBuffer.wrap(B64_DECODER.decode(b641));
                    ByteBuffer bb2 = ByteBuffer.wrap(B64_DECODER.decode(b642));
                    UUID uuid1 = new UUID(bb1.getLong(), bb1.getLong());
                    UUID uuid2 = new UUID(bb2.getLong(), bb2.getLong());
                    return "cron-" + uuid1 + "-" + uuid2;
                } catch (Exception e) {
                    return compressed;
                }
            }
            // Single UUID under "c-" prefix
            if (body.length() >= 22) {
                String b64 = body.substring(0, 22);
                try {
                    byte[] bytes = B64_DECODER.decode(b64);
                    ByteBuffer bb = ByteBuffer.wrap(bytes);
                    UUID uuid = new UUID(bb.getLong(), bb.getLong());
                    return "cron-" + uuid;
                } catch (Exception e) {
                    return compressed;
                }
            }
        }
        // Legacy format: check known prefixes
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
     * Structure-aware truncation: preserves the action prefix and tool name,
     * truncating the session ID portion as last resort (since the session ID
     * can be partially resolved via DB lookup, but the tool name must match exactly).
     */
    private static String truncateCb(String cbInfo) {
        if (cbInfo.length() <= 64) return cbInfo;
        log.warnf("Callback data exceeded 64 bytes and will be truncated: %s", cbInfo);
        int firstPipe = cbInfo.indexOf('|');
        if (firstPipe > 0) {
            int secondPipe = cbInfo.indexOf('|', firstPipe + 1);
            if (secondPipe > 0) {
                String actionPortion = cbInfo.substring(0, firstPipe + 1);
                String toolName = cbInfo.substring(secondPipe + 1);
                int remaining = 64 - actionPortion.length() - toolName.length() - 1;
                if (remaining > 0) {
                    String sessionPart = cbInfo.substring(firstPipe + 1, secondPipe);
                    String truncatedSession = sessionPart.substring(0, Math.min(remaining, sessionPart.length()));
                    return actionPortion + truncatedSession + "|" + toolName;
                }
            }
        }
        return cbInfo.substring(0, 64);
    }

    /**
     * Parses a callback_data string back into a BatchCallbackEvent.
     * Supports short prefixes ("A" / "D") and legacy ("BATCH_APPROVE" / "BATCH_DENY").
     * Returns empty Optional if format is unrecognized.
     */
    static Optional<BatchCallbackEvent> parseCallback(String callbackData) {
        if (callbackData == null) return Optional.empty();

        String[] parts = PIPE_PATTERN.split(callbackData);
        if (parts.length < 2) return Optional.empty();

        String action = parts[0];
        String sessionId = parts[1];

        return switch (action) {
            case "BATCH_APPROVE", "A" -> {
                if (parts.length < 3) yield Optional.empty();
                yield Optional.of(new BatchCallbackEvent.ApproveTool(sessionId, parts[2]));
            }
            case "BATCH_DENY", "D" -> {
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
