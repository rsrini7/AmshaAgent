package com.amsha.common.util;

import java.util.regex.Pattern;

/** Removes credential-shaped values before text reaches logs or diagnostics. */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@");

    private static final Pattern TELEGRAM_API_TOKEN =
            Pattern.compile("(?i)(https?://api\\.telegram\\.org/bot)[^/?#\\s]+");
    private static final Pattern TELEGRAM_TOKEN =
            Pattern.compile("(?<![A-Za-z0-9_-])\\d{6,}:[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?<![A-Za-z0-9_-])(?:[A-Za-z0-9]+[_-])*?(?:authorization|proxy-authorization|api[-_]?keys?|access[-_]?tokens?|auth[-_]?tokens?|bot[-_]?tokens?|client[-_]?secrets?|password)(?:[_-][A-Za-z0-9]+)*\\b\\s*[=:]\\s*[\\\"']?)(?:bearer\\s+)?[^\\s,\\\"';&]+");
    private static final Pattern SK_API_KEY =
            Pattern.compile("(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{16,}(?![A-Za-z0-9_-])");
    private static final Pattern JWT_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");

    /**
     * Minimum message length that could contain any sensitive keyword.
     *
     * <p>The shortest complete sensitive assignment is eight characters ({@code apikey=x}). A
     * shorter message cannot match any pattern, so it can skip the regex pipeline.
     */
    private static final int MIN_MEANINGFUL_LENGTH = 8;

    private SecretRedactor() {}

    public static String redact(String value) {
        if (value == null || value.length() < MIN_MEANINGFUL_LENGTH) return value;

        String redacted = TELEGRAM_API_TOKEN.matcher(value).replaceAll("$1" + REDACTED);
        redacted = URL_CREDENTIALS.matcher(redacted).replaceAll("$1" + REDACTED + "@");
        redacted = TELEGRAM_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = SENSITIVE_ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = SK_API_KEY.matcher(redacted).replaceAll(REDACTED);
        return JWT_TOKEN.matcher(redacted).replaceAll(REDACTED);
    }
}
