package com.amsha.core.agent.reasoning;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.MediaTagValues;

import io.micrometer.core.instrument.MeterRegistry;

public final class MultimodalMetrics {

    static final String INGESTION_TOTAL = "amsha.media.ingestion.total";
    static final String FALLBACK_TOTAL = "amsha.media.fallback.total";
    static final String EXTRACTION_DURATION = "amsha.media.extraction.duration";
    static final String EXTRACTION_CHARS = "amsha.media.extraction.chars";

    private MultimodalMetrics() {}

    public static void recordIngestion(MeterRegistry registry, AttachmentType type, String source, String status) {
        if (registry == null) {
            return;
        }
        try {
            registry.counter(
                            INGESTION_TOTAL,
                            "type",
                            resolveType(type),
                            "source",
                            resolveSource(source),
                            "status",
                            resolveStatus(status))
                    .increment();
        } catch (Exception _) {
            // Metrics must never fail agent reasoning
        }
    }

    public static void recordFallback(MeterRegistry registry, String reason, AttachmentType type) {
        if (registry == null) {
            return;
        }
        try {
            registry.counter(FALLBACK_TOTAL, "reason", resolveReason(reason), "type", resolveType(type))
                    .increment();
        } catch (Exception _) {
            // Metrics must never fail agent reasoning
        }
    }

    public static void recordExtraction(MeterRegistry registry, long durationNanos, int charCount, boolean success) {
        if (registry == null) {
            return;
        }
        try {
            registry.timer(EXTRACTION_DURATION, "type", "document_pdf", "status", success ? "success" : "error")
                    .record(durationNanos, TimeUnit.NANOSECONDS);
            if (success && charCount > 0) {
                registry.counter(EXTRACTION_CHARS, "type", "document_pdf").increment(charCount);
            }
        } catch (Exception _) {
            // Metrics must never fail agent reasoning
        }
    }

    public static String resolveSource(MediaAttachment attachment) {
        return MediaTagValues.resolveSource(attachment);
    }

    public static String resolveSource(String source) {
        return MediaTagValues.resolveSource(source);
    }

    public static String resolveType(AttachmentType type) {
        return MediaTagValues.resolveType(type);
    }

    public static String resolveStatus(String status) {
        return MediaTagValues.resolveStatus(status);
    }

    public static String resolveReason(String reason) {
        return MediaTagValues.resolveReason(reason);
    }

    static String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "unnamed_media";
        }
        String sanitized = name.replaceAll("[\\p{Cntrl}\\r\\n]", "").trim();
        return sanitized.isBlank() ? "unnamed_media" : sanitized;
    }

    static String safeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "active model";
        }
        String sanitized = modelName.replaceAll("[\\p{Cntrl}\\r\\n]", "").trim();
        return sanitized.isBlank() ? "active model" : sanitized;
    }

    static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "unknown size";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String extractPdfText(byte[] pdfBytes, int maxChars) {
        if (pdfBytes == null || pdfBytes.length == 0 || maxChars <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        int btIndex = 0;
        while ((btIndex = raw.indexOf("BT", btIndex)) != -1 && text.length() < maxChars) {
            int etIndex = raw.indexOf("ET", btIndex + 2);
            if (etIndex == -1) {
                break;
            }
            extractLiteralStrings(raw.substring(btIndex + 2, etIndex), text, maxChars);
            btIndex = etIndex + 2;
        }
        if (text.isEmpty()) {
            extractLiteralStrings(raw, text, maxChars);
        }
        String result = text.toString().replaceAll("[\\r\\n]+", "\n").trim();
        return result.length() > maxChars ? result.substring(0, maxChars) + "..." : result;
    }

    private static void extractLiteralStrings(String block, StringBuilder text, int maxChars) {
        int index = 0;
        while (index < block.length() && text.length() < maxChars) {
            int open = block.indexOf('(', index);
            if (open == -1) {
                break;
            }
            int close = -1;
            int depth = 1;
            for (int i = open + 1; i < block.length(); i++) {
                char current = block.charAt(i);
                if (current == '\\') {
                    i++;
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')' && --depth == 0) {
                    close = i;
                    break;
                }
            }
            if (close == -1) {
                break;
            }
            String literal = block.substring(open + 1, close)
                    .replace("\\(", "(")
                    .replace("\\)", ")")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\");
            if (isReadableAscii(literal)) {
                text.append(literal).append(' ');
            }
            index = close + 1;
        }
    }

    private static boolean isReadableAscii(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current >= 32 && current <= 126) || Character.isWhitespace(current)) {
                printable++;
            }
        }
        return (double) printable / value.length() >= 0.7;
    }
}
