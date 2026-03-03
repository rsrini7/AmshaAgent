package com.arcclaw.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentErrorMessageUtil {

    private AgentErrorMessageUtil() {}

    public static String sanitizeErrorMessage(String raw) {
        if (raw == null) return "❌ Task failed. Please try again.";

        // Strip prefixes added by the internal engine
        String stripped = raw.replace("ReasoningEngine error: ", "")
                             .replace("java.lang.RuntimeException: ", "")
                             .replace("TOOL_EXECUTION_FAILED: ", "")
                             .trim();

        // 1. Rock-Solid JSON Extraction (Ignores Regex escaping issues)
        int msgStart = stripped.indexOf("\"message\":\"");
        int typeStart = stripped.indexOf("\",\"type\"");
        
        String apiMessage = stripped;
        boolean isJson = false;

        // If it's a standard OpenAI/Groq JSON error, extract just the message part
        if (msgStart != -1 && typeStart != -1 && typeStart > msgStart) {
            apiMessage = stripped.substring(msgStart + 11, typeStart)
                                 .replace("\\\"", "\"")
                                 .replace("\\n", "\n");
            isJson = true;
        }

        // 2. Route the clean message
        if (apiMessage.contains("tokens per day") || apiMessage.contains("TPD")) {
            return "⚠️ Daily token limit reached. Please try again in " + extractWaitTime(apiMessage) + ".";
        }
        
        if (apiMessage.contains("rate limit") || apiMessage.contains("Rate limit") || apiMessage.contains("throttled")) {
            return "⏳ I'm temporarily throttled. Please try again in " + extractWaitTime(apiMessage) + ".";
        }
        
        if (apiMessage.contains("tool call validation failed") || apiMessage.contains("SCHEMA_ERROR")) {
            return "❌ Tool execution error: The agent generated an invalid request.";
        }
        
        if (apiMessage.contains("Budget Exceeded")) {
            return "❌ Your session budget limit has been reached.";
        }

        // 3. Ultimate Fallbacks
        if (isJson) {
            // We successfully pulled the string out of JSON, but it didn't match our known errors.
            // It is safe to show to the user.
            return "⚠️ API Error: " + apiMessage;
        }

        // If it IS a JSON string but our extraction failed (e.g., malformed), hide it so we don't dump JSON in chat
        if (stripped.contains("{") && stripped.contains("\"error\"")) {
            return "⚠️ The AI service returned an error. Please try again shortly.";
        }

        // Otherwise, it's just safe, plain text
        return stripped;
    }

    private static String extractWaitTime(String text) {
        // Looks for "Please try again in 26m47.04s", "5m", or "120s"
        Matcher m = Pattern.compile("Please try again in ([\\d]+m[\\d.]+s|[\\d.]+s|[\\d]+m)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            return humanReadableWait(m.group(1));
        }
        return "a few moments";
    }

    private static String humanReadableWait(String rawInterval) {
        int minutes = 0;
        int seconds = 0;
        String s = rawInterval.trim();

        Matcher m1 = Pattern.compile("(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s)?").matcher(s);
        if (m1.matches()) {
            if (m1.group(1) != null) minutes = Integer.parseInt(m1.group(1));
            if (m1.group(2) != null) seconds = (int) Math.round(Double.parseDouble(m1.group(2)));
        } else if (s.endsWith("s")) {
            String num = s.substring(0, s.length() - 1);
            seconds = (int) Math.round(Double.parseDouble(num));
        }

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