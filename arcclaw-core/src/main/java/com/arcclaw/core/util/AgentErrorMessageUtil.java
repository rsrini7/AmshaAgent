package com.arcclaw.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentErrorMessageUtil {

    private AgentErrorMessageUtil() {}

    public static String extractWaitTime(String errorJson) {
        if (errorJson == null || errorJson.isBlank()) {
            return "a few moments";
        }
        try {
            Pattern pattern = Pattern.compile(
                "Please try again in (\\d+m)?(\\d+(?:\\.\\d+)?s)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = pattern.matcher(errorJson);
            if (!matcher.find()) return "a few moments";

            String minutePart = matcher.group(1);
            String secondPart = matcher.group(2);
            int minutes = minutePart != null
                ? Integer.parseInt(minutePart.replace("m", "").trim()) : 0;
            int seconds = secondPart != null
                ? (int) Double.parseDouble(secondPart.replace("s", "").trim()) : 0;

            if (minutes > 0 && seconds > 0)
                return minutes + " minute" + (minutes > 1 ? "s" : "") +
                       " and " + seconds + " second" + (seconds != 1 ? "s" : "");
            if (minutes > 0)
                return minutes + " minute" + (minutes > 1 ? "s" : "");
            if (seconds > 0)
                return seconds + " second" + (seconds != 1 ? "s" : "");
            return "a few moments";

        } catch (Exception e) {
            return "a few moments";
        }
    }

    public static String sanitizeErrorMessage(String raw) {
        if (raw == null) return "❌ Task failed. Please try again.";

        // Strip prefix added by processEngineStep before re-checking
        String stripped = raw.startsWith("ReasoningEngine error: ")
            ? raw.substring("ReasoningEngine error: ".length())
            : raw;

        // Already-formatted throttle message — pass through unchanged
        if (stripped.contains("⚠️") &&
            (stripped.contains("throttled") || stripped.contains("token limit"))) {
            return stripped;
        }

        // Per-minute rate limit
        if (stripped.contains("rate_limit_exceeded") && stripped.contains("Please try again in")) {
            return buildThrottleMessage(stripped);
        }

        // Per-day token limit (TPD)
        if (stripped.contains("rate_limit_exceeded") && stripped.contains("tokens per day")) {
            return buildTpdThrottleMessage(stripped);
        }

        // Generic Groq error wrapper
        if (raw.contains("ReasoningEngine error") || raw.contains("\"error\"")) {
            return "⚠️ The AI service returned an error. Please try again shortly.";
        }

        if (raw.contains("SCHEMA_ERROR")) {
            return "❌ tool execution error: The agent generated an invalid request.";
        }
        
        if (raw.contains("Budget Exceeded")) {
            return "❌ Your session budget limit has been reached.";
        }

        return raw;
    }

    private static String buildThrottleMessage(String raw) {
        Matcher m = Pattern
            .compile("Please try again in (\\d+m\\d+(?:\\.\\d+)?s|\\d+(?:\\.\\d+)?s|\\d+m)")
            .matcher(raw);
        if (m.find()) {
            String wait = humanReadableWait(m.group(1));
            return "⚠️ I'm temporarily throttled. Please try again in " + wait + ".";
        }
        return "⚠️ I'm temporarily throttled. Please try again in a few minutes.";
    }

    // handles TPD limit which includes "Xm Ys" format like "39m48.96s"
    private static String buildTpdThrottleMessage(String raw) {
        Matcher m = Pattern
                .compile("Please try again in ([\\d]+m[\\d.]+s|[\\d.]+s|[\\d]+m)")
                .matcher(raw);
        if (m.find()) {
            String wait = humanReadableWait(m.group(1));
            return "⚠️ Daily token limit reached. Please try again in " + wait + ".";
        }
        return "⚠️ Daily token limit reached. Please try again later today.";
    }

    private static String humanReadableWait(String rawInterval) {
        // rawInterval examples: "39m48.96s", "120s", "5m"
        int minutes = 0;
        int seconds = 0;

        String s = rawInterval.trim();

        // Match "<minutes>m<seconds>s" or "<minutes>m"
        Matcher m1 = Pattern
                .compile("(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?")
                .matcher(s);
        if (m1.matches()) {
            if (m1.group(1) != null) {
                minutes = Integer.parseInt(m1.group(1));
            }
            if (m1.group(2) != null) {
                double sec = Double.parseDouble(m1.group(2));
                seconds = (int) Math.round(sec);
            }
        } else if (s.endsWith("s")) {
            // Fallback: "120s"
            String num = s.substring(0, s.length() - 1);
            seconds = (int) Math.round(Double.parseDouble(num));
        }

        // Normalize >60s into minutes
        minutes += seconds / 60;
        seconds = seconds % 60;

        if (minutes > 0 && seconds > 0) {
            return minutes + " minutes and " + seconds + " seconds";
        } else if (minutes > 0) {
            return minutes + " minutes";
        } else if (seconds > 0) {
            return seconds + " seconds";
        } else {
            return "a few moments";
        }
    }
}
