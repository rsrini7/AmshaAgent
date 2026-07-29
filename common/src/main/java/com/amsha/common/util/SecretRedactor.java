package com.amsha.common.util;

import java.util.regex.Pattern;

/** Removes credential-shaped values before text reaches logs or diagnostics. */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern TELEGRAM_API_TOKEN =
            Pattern.compile("(?i)(https?://api\\.telegram\\.org/bot)[^/?#\\s]+");
    private static final Pattern TELEGRAM_TOKEN =
            Pattern.compile("(?<![A-Za-z0-9_-])\\d{6,}:[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization|api[-_]?key|access[-_]?token|auth[-_]?token|bot[-_]?token|client[-_]?secret|password)\\b\\s*[=:]\\s*[\\\"']?)(?:bearer\\s+)?[^\\s,\\\"';&]+");

    private SecretRedactor() {}

    public static String redact(String value) {
        if (value == null || value.isEmpty()) return value;

        String redacted = TELEGRAM_API_TOKEN.matcher(value).replaceAll("$1" + REDACTED);
        redacted = TELEGRAM_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1" + REDACTED);
        return SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
    }
}
