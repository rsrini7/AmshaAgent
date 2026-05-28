package com.arcclaw.core.util;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

/**
 * Resolver for extracting target UTC times from natural language scheduling requests.
 * Addresses the "antigravity principle" by performing all date/time arithmetic in Java
 * instead of relying on LLM logic.
 *
 * All patterns are static final to comply with Architecture Invariant 9.1.
 */
public class CronTimeResolver {

    // Handles: "in 5 min", "in a min", "in an hour", "after 10 minutes", "in one hour"
    private static final Pattern IN_RELATIVE_TIME_PATTERN = Pattern.compile(
            "(?:in|after)\\s+(?:exactly\\s+)?(a|an|one|\\d+)\\s*(min(?:utes?)?|hours?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern AT_TIME_PATTERN =
            Pattern.compile("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern VALID_CRON_PATTERN =
            Pattern.compile("^([*\\d/]+)\\s+([*\\d/]+)\\s+([*\\d/]+)\\s+([*\\d/]+)\\s+([*\\d/]+)$");

    // Handles: "every minute", "every 5 minutes", "every hour", "every 2 hours"
    private static final Pattern EVERY_PATTERN =
            Pattern.compile("every\\s+(?:(\\d+)\\s+)?(min(?:utes?)?|hours?)", Pattern.CASE_INSENSITIVE);

    // Negation guard (Invariant 4.11)
    private static final Pattern NEGATION_GUARD =
            Pattern.compile("\\b(?:not|don't|do not|never|cancel|stop|remove)\\b", Pattern.CASE_INSENSITIVE);

    public static boolean isValidCron(String cron) {
        return cron != null && VALID_CRON_PATTERN.matcher(cron.trim()).matches();
    }

    /**
     * Resolves recurring scheduling patterns ("every X minutes/hours") into
     * a 5-field UNIX cron expression.
     *
     * <p>This runs on the Java side (zero LLM cost) so the LLM does not need
     * to generate cron expressions for common recurring patterns. Returns
     * {@code null} if the message does not contain a recognisable recurring
     * pattern, allowing the caller to skip injection.
     *
     * @param msg raw user message text
     * @return a 5-field UNIX cron expression, or {@code null}
     */
    public static String resolveRecurringCron(String msg) {
        if (msg == null) return null;
        if (NEGATION_GUARD.matcher(msg).find()) return null;

        var m = EVERY_PATTERN.matcher(msg);
        if (m.find()) {
            String amount = m.group(1);
            String unit = m.group(2).toLowerCase();

            if (unit.startsWith("min")) {
                if (amount == null || "1".equals(amount)) return "*/1 * * * *";
                return "*/" + amount + " * * * *";
            }
            if (unit.startsWith("hour")) {
                if (amount == null || "1".equals(amount)) return "0 * * * *";
                return "0 */" + amount + " * * *";
            }
        }
        return null;
    }

    public static ZonedDateTime resolveTargetTime(String msg, ZonedDateTime nowUtc, ZoneId userZone) {
        if (msg == null) return null;

        // Invariant 4.11 - Bail immediately on negation
        if (NEGATION_GUARD.matcher(msg).find()) {
            return null; // caller must handle null → do not schedule
        }

        var m1 = IN_RELATIVE_TIME_PATTERN.matcher(msg);
        if (m1.find()) {
            String q = m1.group(1);
            String unit = m1.group(2).toLowerCase();
            long amount = (q.equalsIgnoreCase("a") || q.equalsIgnoreCase("an") || q.equalsIgnoreCase("one"))
                    ? 1L
                    : Long.parseLong(q);

            if (unit.startsWith("hour")) {
                return nowUtc.plusHours(amount);
            } else {
                return nowUtc.plusMinutes(amount);
            }
        }

        var m2 = AT_TIME_PATTERN.matcher(msg);
        if (m2.find()) {
            int hour = Integer.parseInt(m2.group(1));
            int minute = m2.group(2) != null ? Integer.parseInt(m2.group(2)) : 0;
            if ("pm".equalsIgnoreCase(m2.group(3)) && hour < 12) hour += 12;
            if ("am".equalsIgnoreCase(m2.group(3)) && hour == 12) hour = 0;

            ZonedDateTime nowLocal = ZonedDateTime.now(userZone);
            ZonedDateTime local =
                    nowLocal.withHour(hour).withMinute(minute).withSecond(0).withNano(0);

            if (local.isBefore(nowLocal)) local = local.plusDays(1);
            return local.withZoneSameInstant(ZoneOffset.UTC);
        }

        return null; // No match = not a scheduling request; caller skips injection
    }
}
