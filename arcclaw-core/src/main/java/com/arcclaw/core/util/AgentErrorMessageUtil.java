package com.arcclaw.core.util;

public final class AgentErrorMessageUtil {

    private AgentErrorMessageUtil() {}

    public static String extractWaitTime(String errorJson) {
        if (errorJson == null || errorJson.isBlank()) {
            return "a few moments";
        }
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "Please try again in (\\d+m)?(\\d+(?:\\.\\d+)?s)",
                java.util.regex.Pattern.CASE_INSENSITIVE
            );
            java.util.regex.Matcher matcher = pattern.matcher(errorJson);
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

    public static String sanitizeErrorMessage(String terminationReason) {
        if (terminationReason == null) {
            return "❌ Task failed. Please try again.";
        }
        if (terminationReason.contains("rate_limit_exceeded")) {
            String wait = extractWaitTime(terminationReason);
            return "⚠️ I'm temporarily throttled due to high demand. " +
                   "Please try again in " + wait + ".";
        }
        if (terminationReason.contains("SCHEMA_ERROR")) {
            return "❌ I encountered a tool execution error. " +
                   "Please rephrase your request.";
        }
        if (terminationReason.contains("Budget Exceeded")) {
            return "⚠️ Session budget limit reached. " +
                   "Please start a new session.";
        }
        if (terminationReason.contains("SYSTEM_ERROR")) {
            return "❌ An internal error occurred. Please try again.";
        }
        return "❌ Task failed. Please try again.";
    }
}
