package com.arcclaw.core.util;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.arcclaw.core.pipeline.MessageContext;
import org.jboss.logging.Logger;

/**
 * Utility to inject scheduling metadata into a {@link MessageContext} based on
 * natural language analysis of user input.
 *
 * <p>Centralizes logic for:
 * <ul>
 *   <li>Resolving specific target times (e.g. "at 9 AM") into UTC</li>
 *   <li>Resolving recurring patterns (e.g. "every minute")</li>
 *   <li>Injecting SUGGESTED_CRON_SOURCE="UTC" to prevent double timezone conversion</li>
 * </ul>
 */
public class TimeContextInjector {

    private static final Logger log = Logger.getLogger(TimeContextInjector.class);

    /**
     * Analyzes input and injects scheduling hints into the context.
     *
     * @param context the context to enrich
     * @param input the raw user message
     * @param defaultTimezone the timezone to use for relative time calculation (e.g. "Asia/Kolkata")
     */
    public static void inject(MessageContext context, String input, String defaultTimezone) {
        if (input == null || input.isBlank()) return;

        ZoneId userZone = ZoneId.of(defaultTimezone);
        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);

        // 1. Try to resolve a specific instant (e.g. "at 9 AM")
        ZonedDateTime targetUtc = CronTimeResolver.resolveTargetTime(input, nowUtc, userZone);
        if (targetUtc != null) {
            context.setData("SUGGESTED_CRON", String.format("%d %d * * *",
                    targetUtc.getMinute(), targetUtc.getHour()));
            context.setData("TARGET_UTC_MINUTE", targetUtc.getMinute());
            context.setData("TARGET_UTC_HOUR", targetUtc.getHour());
            context.setData("SUGGESTED_NEXT_FIRE_TIME",
                    targetUtc.toInstant().toString());
            
            // For older code paths that expect these keys
            context.setData("resolvedRunAt", targetUtc.toInstant().toString());
            context.setData("resolvedRunAtInstant", targetUtc.toInstant());

            context.setData("CURRENT_LOCAL_TIME",
                    ZonedDateTime.now(userZone).format(DateTimeFormatter.ofPattern("HH:mm z")));
            
            // FIX: Mark the cron as pre-resolved to UTC so ScheduleTaskTool
            // does not double-convert when the LLM also passes a timezone.
            context.setData("SUGGESTED_CRON_SOURCE", "UTC");

            log.infof("[TimeContextInjector] Instant resolved: UTC %02d:%02d for input '%s'",
                    targetUtc.getHour(), targetUtc.getMinute(), input);
        } else {
            // 2. Fallback: Check for recurring patterns ("every X minutes/hours")
            String recurringCron = CronTimeResolver.resolveRecurringCron(input);
            if (recurringCron != null) {
                context.setData("SUGGESTED_CRON", recurringCron);
                log.infof("[TimeContextInjector] Recurring cron resolved: '%s' for input '%s'",
                        recurringCron, input);
            }
        }
    }
}
