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

    // All patterns static final — compiled once, never inline (Invariant 9.1)
    private static final Pattern IN_MINUTES_PATTERN =
            Pattern.compile("(?:in|after)\\s+(\\d+)\\s*min(?:utes?)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern AT_TIME_PATTERN =
            Pattern.compile("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE);

    private static final Pattern VALID_CRON_PATTERN =
            Pattern.compile("^\\d{1,2}\\s+\\d{1,2}\\s+[*\\d]+\\s+[*\\d]+\\s+[*\\d]+$");

    // Negation guard (Invariant 4.11)
    private static final Pattern NEGATION_GUARD =
            Pattern.compile("\\b(?:not|don't|do not|never|cancel|stop|remove)\\b",
                    Pattern.CASE_INSENSITIVE);

    public static boolean isValidCron(String cron) {
        return cron != null && VALID_CRON_PATTERN.matcher(cron.trim()).matches();
    }

    public static ZonedDateTime resolveTargetTime(String msg, ZonedDateTime nowUtc, ZoneId userZone) {
        if (msg == null) return null;

        // Invariant 4.11 — Bail immediately on negation
        if (NEGATION_GUARD.matcher(msg).find()) {
            return null; // caller must handle null → do not schedule
        }

        var m1 = IN_MINUTES_PATTERN.matcher(msg);
        if (m1.find()) {
            return nowUtc.plusMinutes(Long.parseLong(m1.group(1)));
        }

        var m2 = AT_TIME_PATTERN.matcher(msg);
        if (m2.find()) {
            int hour   = Integer.parseInt(m2.group(1));
            int minute = m2.group(2) != null ? Integer.parseInt(m2.group(2)) : 0;
            if ("pm".equalsIgnoreCase(m2.group(3)) && hour < 12) hour += 12;
            if ("am".equalsIgnoreCase(m2.group(3)) && hour == 12) hour  = 0;
            
            ZonedDateTime nowLocal = ZonedDateTime.now(userZone);
            ZonedDateTime local = nowLocal
                    .withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            
            if (local.isBefore(nowLocal)) local = local.plusDays(1);
            return local.withZoneSameInstant(ZoneOffset.UTC);
        }

        return nowUtc.plusMinutes(5); // safe fallback
    }
}
