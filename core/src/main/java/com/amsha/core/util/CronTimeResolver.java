package com.amsha.core.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
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

    private static final Pattern SCHEDULE_VERB_PATTERN = Pattern.compile(
            "\\b(?:schedule|scheduled|remind|reminder|timer|cron|recurring|repeat)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ACTION_VERB_PATTERN = Pattern.compile(
            "\\b(?:run|execute|write|send|check|perform|do|create|fetch|search)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEDULE_PREFIX_PATTERN = Pattern.compile(
            "^\\s*(?:please\\s+)?(?:schedule(?:\\s+(?:a|the))?|remind(?:\\s+me)?|"
                    + "set\\s+(?:a\\s+)?(?:timer|reminder)|create\\s+(?:a\\s+)?(?:cron|schedule)|cron)"
                    + "\\s*(?:task)?\\s*(?:to|that|:)?\\s*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Deterministic representation of an unambiguous scheduling request.
     *
     * <p>{@code cronExpression} is populated for recurring requests and
     * {@code targetTime} is populated for one-shot requests. The unused value is
     * {@code null}; callers must use {@link #runOnce()} to select the field.
     */
    public record ScheduleRequest(
            String goal, boolean runOnce, String cronExpression, ZoneId timezone, ZonedDateTime targetTime) {
        public ScheduleRequest {
            if (goal == null || goal.isBlank()) {
                throw new IllegalArgumentException("Schedule goal must not be blank");
            }
            Objects.requireNonNull(timezone, "timezone");
            if (runOnce && targetTime == null) {
                throw new IllegalArgumentException("One-shot schedule requires a target time");
            }
            if (!runOnce && (cronExpression == null || cronExpression.isBlank())) {
                throw new IllegalArgumentException("Recurring schedule requires a cron expression");
            }
        }
    }

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
            String unit = m.group(2).toLowerCase(Locale.ROOT);

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
            String unit = m1.group(2).toLowerCase(Locale.ROOT);
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

            ZonedDateTime nowLocal = nowUtc.withZoneSameInstant(userZone);
            ZonedDateTime local =
                    nowLocal.withHour(hour).withMinute(minute).withSecond(0).withNano(0);

            if (local.isBefore(nowLocal)) local = local.plusDays(1);
            return local.withZoneSameInstant(ZoneOffset.UTC);
        }

        return null; // No match = not a scheduling request; caller skips injection
    }

    /**
     * Parses only explicit, unambiguous schedule requests without calling an LLM.
     *
     * <p>This is deliberately narrower than a general natural-language parser. A
     * temporal phrase must be accompanied by a scheduling or action verb, a usable
     * goal must remain after removing the time expression, and negated requests are
     * rejected. Ambiguous text remains on the normal reasoning path.
     */
    public static Optional<ScheduleRequest> parseSchedulingRequest(String message, ZoneId userZone) {
        return parseSchedulingRequest(message, ZonedDateTime.now(ZoneOffset.UTC), userZone);
    }

    /** Testable overload using a caller-provided UTC clock snapshot. */
    public static Optional<ScheduleRequest> parseSchedulingRequest(
            String message, ZonedDateTime nowUtc, ZoneId userZone) {
        if (message == null || message.isBlank() || nowUtc == null || userZone == null) {
            return Optional.empty();
        }
        if (NEGATION_GUARD.matcher(message).find()) {
            return Optional.empty();
        }

        Matcher recurring = EVERY_PATTERN.matcher(message);
        Matcher relative = IN_RELATIVE_TIME_PATTERN.matcher(message);
        Matcher absolute = AT_TIME_PATTERN.matcher(message);
        Matcher temporal =
                recurring.find() ? recurring : relative.find() ? relative : absolute.find() ? absolute : null;
        if (temporal == null || !hasSchedulingVerb(message)) {
            return Optional.empty();
        }

        String goal = removeTemporalExpression(message, temporal);
        if (goal.isBlank()) {
            return Optional.empty();
        }

        goal = SCHEDULE_PREFIX_PATTERN.matcher(goal).replaceFirst("").strip();
        goal = goal.replaceFirst("(?i)^(?:to|that)\\s+", "").strip();
        goal = goal.replaceFirst("^[,;:.]\\s*", "").strip();
        if (goal.isBlank()) {
            return Optional.empty();
        }

        if (recurring.pattern().equals(temporal.pattern())) {
            String cron = resolveRecurringCron(message);
            return cron == null
                    ? Optional.empty()
                    : Optional.of(new ScheduleRequest(goal, false, cron, userZone, null));
        }

        ZonedDateTime target = resolveTargetTime(message, nowUtc, userZone);
        return target == null ? Optional.empty() : Optional.of(new ScheduleRequest(goal, true, null, userZone, target));
    }

    private static boolean hasSchedulingVerb(String message) {
        return SCHEDULE_VERB_PATTERN.matcher(message).find()
                || ACTION_VERB_PATTERN.matcher(message).find();
    }

    private static String removeTemporalExpression(String message, Matcher temporal) {
        String withoutTime = message.substring(0, temporal.start()) + " " + message.substring(temporal.end());
        return withoutTime
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([,;:.])", "$1")
                .strip();
    }

    /**
     * Resolves a one-shot target against CURRENT server time rather than against a
     * caller-supplied "now".
     *
     * <p>{@link #resolveTargetTime} is normally called once, upstream, to build the LLM's
     * time context. That snapshot is captured before inference; when inference is slow the
     * resulting absolute target is already in the past by the time a tool acts on it. A
     * run-once cron then silently rolls over to its next occurrence, turning "in 1 minute"
     * into "same minute tomorrow". Re-resolving at execution time keeps the outcome
     * independent of inference latency.
     *
     * @param msg raw user message text
     * @param userZone zone used to interpret absolute times ("at 9 AM")
     * @param minLead minimum lead time the target must still retain; a target falling
     *                inside it is treated as stale and rejected
     * @return the resolved target, or {@code null} when the wording carries no temporal
     *         intent or the target is already stale (caller must fall back or fail)
     */
    public static ZonedDateTime resolveTargetTimeFromNow(String msg, ZoneId userZone, Duration minLead) {
        Instant now = Instant.now();
        ZonedDateTime target = resolveTargetTime(msg, ZonedDateTime.ofInstant(now, ZoneOffset.UTC), userZone);
        if (target == null) return null;
        Instant earliest = now.plus(minLead);
        return target.toInstant().isBefore(earliest) ? null : target;
    }
}
