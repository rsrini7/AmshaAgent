package com.arcclaw.core.util;

import static com.arcclaw.core.message.UserMessages.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcclaw.core.message.UserMessages;
import org.jboss.logging.Logger;
import com.arcclaw.common.util.LogUtil;

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

    private static final Pattern RETRY_AFTER_HEADER_PATTERN = Pattern.compile("(?i)retry-after:\\s*(\\d+)");

    private static final Logger log = Logger.getLogger(AgentErrorMessageUtil.class);

    private AgentErrorMessageUtil() {}
 
    /**
     * Returns a safe message from a Throwable.
     * Returns e.getMessage() if present, otherwise returns the clean class simple name.
     * If the throwable is null, returns "Unknown Error".
     */
    public static String safeMessage(Throwable t) {
        if (t == null) return "Unknown Error";
        return t.getMessage() != null ? t.getMessage() : ClassUtils.getCleanName(t);
    }
 
    public static String sanitizeErrorMessage(String raw) {
        if (raw == null) return TASK_FAILED;

        // Guard: if the raw message looks like a JVM internal crash message,
        // return a safe fallback immediately - never show stack-trace internals to users.
        if (JVM_INTERNALS_PATTERN.matcher(raw.trim()).find()) {
            return AI_UNEXPECTED_RESPONSE;
        }

        String stripped = raw.replace("ReasoningEngine error: ", "")
                             .replace("java.lang.RuntimeException: ", "")
                             .replace("dev.langchain4j.exception.HttpException: ", "")
                             .replace("TOOL_EXECUTION_FAILED: ", "")
                             .trim();

        String apiMessage = stripped;
        boolean isJson = false;

        Matcher messageMatcher = Pattern.compile("\"(?:message|detail)\"\\s*:\\s*\"(.*?)(?<!\\\\)\"").matcher(stripped);
        if (messageMatcher.find()) {
            apiMessage = messageMatcher.group(1)
                                  .replace("\\\"", "\"")
                                  .replace("\\n", "\n");
            isJson = true;
        }

        if (apiMessage.contains("model_not_found") || apiMessage.contains("does not exist")) {
            return UserMessages.modelNotFound(extractModelName(apiMessage));
        }

        if (apiMessage.contains("tokens per day") || apiMessage.contains("TPD")) {
            return formatTpdMessage(apiMessage);
        }

        if (isRateLimitError(apiMessage)) {
            return UserMessages.rateLimited(extractWaitTime(apiMessage));
        }

        if (apiMessage.contains("over capacity")) {
            return OVER_CAPACITY;
        }

        if (apiMessage.contains("tool call validation failed") || apiMessage.contains("SCHEMA_ERROR")) {
            return TOOL_VALIDATION_ERROR;
        }

        if (apiMessage.contains("Budget Exceeded")) {
            return BUDGET_EXCEEDED;
        }

        if (isJson) {
            return UserMessages.apiError(apiMessage);
        }

        if (stripped.contains("{") && stripped.contains("\"error\"")) {
            return AI_SERVICE_ERROR;
        }

        return stripped;
    }

    private static final Pattern LLM_ERROR_NARRATION_PATTERN = Pattern.compile(
        "(?i).*(?:" +
        "provided function definitions do not match the needs|" +
        "function definitions were provided but no function call|" +
        "model produced an invalid response|" +
        "failed to call tool" +
        ").*",
        Pattern.DOTALL
    );

    /**
     * Strips known Groq/OpenAI error phrases from FINISH answers.
     * These phrases are sometimes hallucinated into the answer text when the LLM
     * fails to use a tool properly in a cold-context session.
     *
     * TIGHTENING: Only sanitize if the response is short (< 500 chars), to avoid
     * wiping long research answers that might incidentally contain these phrases.
     */
    public static String sanitizeLlmErrorPhrases(String answer) {
        if (answer == null || answer.isBlank()) return answer;
        if (answer.length() < 500 && LLM_ERROR_NARRATION_PATTERN.matcher(answer).find()) {
            return INTERNAL_LLM_ERROR;
        }
        return answer;
    }

    private static String formatTpdMessage(String apiMessage) {
        String waitTime = extractWaitTime(apiMessage);
        Matcher tokenMatcher = TOKEN_LIMIT_PATTERN.matcher(apiMessage);

        if (tokenMatcher.find()) {
            String limit = tokenMatcher.group(1);
            String used  = tokenMatcher.group(2);
            String req   = tokenMatcher.group(3);
            return UserMessages.tokenLimitDetailed(limit, used, req, waitTime);
        }

        return UserMessages.tokenLimitSimple(waitTime);
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

    public static boolean isRateLimitError(String msg) {
        if (msg == null) return false;
        return msg.contains("rate limit") || msg.contains("Rate limit") || msg.contains("throttled");
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
            } catch (NumberFormatException _) {
                log.debugf("Failed to parse duration seconds: %s", s);
                return "a few moments";
            }
        } else {
            // Unrecognised format - safe fallback, never crash
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

    public static String hashGoal(String goal) {
        if (goal == null) return "none";
        return String.valueOf(goal.hashCode()); // Simple hash for audit chaining
    }

    private static String extractModelName(String message) {
        if (message == null) return "unknown";
        // Matches: model `name`, model "name", or model name (until space)
        Matcher m = Pattern.compile("model [`'\"]?([^ `'\"]+)[`'\"]?").matcher(message);
        if (m.find()) return m.group(1);
        return "unknown";
    }

    /**
     * Extracts a retry-after delay in milliseconds from an exception message
     * or cause hierarchy. Returns 0 if no retry-after hint is found.
     */
    public static long parseRetryAfterMillis(Throwable t) {
        if (t == null) return 0;
        String msg = t.getMessage();
        if (msg != null) {
            Matcher m = RETRY_AFTER_HEADER_PATTERN.matcher(msg);
            if (m.find()) {
                try {
                    return Long.parseLong(m.group(1)) * 1000L;
                } catch (NumberFormatException e) {
                    log.debugf("Failed to parse retry-after header value: %s", LogUtil.rootMessage(e));
                }
            }
            // Also check for "try again in X.Ys" format
            Matcher m2 = RETRY_AFTER_PATTERN.matcher(msg);
            if (m2.find()) {
                String wait = m2.group(1);
                if (wait.endsWith("s")) {
                    try {
                        return (long) (Double.parseDouble(wait.substring(0, wait.length() - 1)) * 1000L);
                    } catch (NumberFormatException e) {
                        log.debugf("Failed to parse retry-after duration: %s", LogUtil.rootMessage(e));
                    }
                }
            }
        }
        return parseRetryAfterMillis(t.getCause());
    }
}
