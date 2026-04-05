package com.arcclaw.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentErrorMessageUtil {

    private static final Pattern TOKEN_LIMIT_PATTERN = Pattern.compile("Limit (\\d+), Used (\\d+), Requested (\\d+)");
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("Please try again in ([\\dhms.]+)");
    private static final Pattern DURATION_PATTERN    = Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?");

    /**
     * JVM internal exception message patterns that should never be surfaced to users.
     * These indicate a lower-level framework crash (e.g. Retry-After header parse failure)
     * rather than a meaningful API error.
     */
    private static final Pattern JVM_INTERNALS_PATTERN = Pattern.compile(
            "^(For input string:|null|Cannot invoke|NullPointerException|NumberFormatException)");

    private AgentErrorMessageUtil() {}

    public static String sanitizeErrorMessage(String raw) {
        if (raw == null) return "❌ Task failed. Please try again.";

        // Guard: if the raw message looks like a JVM internal crash message,
        // return a safe fallback immediately — never show stack-trace internals to users.
        if (JVM_INTERNALS_PATTERN.matcher(raw.trim()).find()) {
            return "⚠️ The AI service returned an unexpected response. Please try again.";
        }

        String stripped = raw.replace("ReasoningEngine error: ", "")
                             .replace("java.lang.RuntimeException: ", "")
                             .replace("dev.langchain4j.exception.HttpException: ", "")
                             .replace("TOOL_EXECUTION_FAILED: ", "")
                             .trim();

        String apiMessage = stripped;
        boolean isJson = false;

        Matcher messageMatcher = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)(?<!\\\\)\"").matcher(stripped);
        if (messageMatcher.find()) {
            apiMessage = messageMatcher.group(1)
                                  .replace("\\\"", "\"")
                                  .replace("\\n", "\n");
            isJson = true;
        }

        if (apiMessage.contains("model_not_found") || apiMessage.contains("does not exist")) {
            return "⚠️ **Model Not Found**: The AI model `" + extractModelName(apiMessage) + "` is either not available or you don't have access to it. Please check your configuration.";
        }

        if (apiMessage.contains("tokens per day") || apiMessage.contains("TPD")) {
            return formatTpdMessage(apiMessage);
        }

        if (apiMessage.contains("rate limit") || apiMessage.contains("Rate limit") || apiMessage.contains("throttled")) {
            return "⏳ I'm a little busy right now - please give me a moment and try again in " + extractWaitTime(apiMessage) + ".";
        }

        if (apiMessage.contains("over capacity")) {
            return "⚠️ The AI service is currently over capacity and cannot process requests. Please try again in a few minutes.";
        }

        if (apiMessage.contains("tool call validation failed") || apiMessage.contains("SCHEMA_ERROR")) {
            return "❌ Tool execution error: The agent generated an invalid request.";
        }

        if (apiMessage.contains("Budget Exceeded")) {
            return "❌ Your session budget limit has been reached.";
        }

        if (isJson) {
            return "⚠️ API Error: " + apiMessage;
        }

        if (stripped.contains("{") && stripped.contains("\"error\"")) {
            return "⚠️ The AI service returned an error. Please try again shortly.";
        }

        return stripped;
    }

    private static String formatTpdMessage(String apiMessage) {
        String waitTime = extractWaitTime(apiMessage);
        Matcher tokenMatcher = TOKEN_LIMIT_PATTERN.matcher(apiMessage);

        if (tokenMatcher.find()) {
            String limit = tokenMatcher.group(1);
            String used  = tokenMatcher.group(2);
            String req   = tokenMatcher.group(3);
            return String.format("⚠️ **Daily Token Limit Reached**\n" +
                                 "• Limit: %s\n" +
                                 "• Used: %s\n" +
                                 "• Requested: %s\n\n" +
                                 "⏳ Please try again in %s.",
                                 limit, used, req, waitTime);
        }

        return "⚠️ Daily token limit reached. Please try again in " + waitTime + ".";
    }

    private static String extractWaitTime(String text) {
        Matcher m = RETRY_AFTER_PATTERN.matcher(text);
        if (m.find()) {
            String timeStr = m.group(1);
            if (timeStr.endsWith(".")) {
                timeStr = timeStr.substring(0, timeStr.length() - 1);
            }
            return humanReadableWait(timeStr);
        }
        return "a few moments";
    }

    private static String humanReadableWait(String rawInterval) {
        int hours   = 0;
        int minutes = 0;
        int seconds = 0;
        String s = rawInterval.trim().toLowerCase();

        Matcher m1 = DURATION_PATTERN.matcher(s);

        if (m1.matches()) {
            if (m1.group(1) != null) hours   = Integer.parseInt(m1.group(1));
            if (m1.group(2) != null) minutes = Integer.parseInt(m1.group(2));
            if (m1.group(3) != null) seconds = (int) Math.round(Double.parseDouble(m1.group(3)));
        } else if (s.endsWith("s")) {
            try {
                seconds = (int) Math.round(Double.parseDouble(s.substring(0, s.length() - 1)));
            } catch (NumberFormatException ignored) {
                return "a few moments";
            }
        } else {
            // Unrecognised format — safe fallback, never crash
            return "a few moments";
        }

        // Normalize
        minutes += seconds / 60;
        seconds  = seconds % 60;
        hours   += minutes / 60;
        minutes  = minutes % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0)   sb.append(hours).append(hours == 1 ? " hour" : " hours");
        if (minutes > 0) { if (sb.length() > 0) sb.append(" "); sb.append(minutes).append(minutes == 1 ? " minute" : " minutes"); }
        if (seconds > 0) { if (sb.length() > 0) sb.append(" and "); sb.append(seconds).append(seconds == 1 ? " second" : " seconds"); }

        String result = sb.toString();
        return result.isEmpty() ? "a few moments" : result;
    }

    private static String extractModelName(String message) {
        if (message == null) return "unknown";
        // Matches: model `name`, model "name", or model name (until space)
        Matcher m = Pattern.compile("model [`'\"]?([^ `'\"]+)[`'\"]?").matcher(message);
        if (m.find()) return m.group(1);
        return "unknown";
    }
}
