package com.arcclaw.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;

/**
 * Shared logic for rendering {@link AgentResult} to user-friendly text.
 * Unified across interactive sessions and background cron tasks.
 *
 * Added sanitize() to strip raw TOOLCALL markup that OpenRouter free-tier
 * models occasionally embed inside FINISH answer text. Groq never did this because
 * it followed the format schema strictly; OpenRouter free models do not.
 */
public class AgentResultFormatter {

    // TOOLCALL sanitization patterns

    /**
     * Full XML-style block: <TOOLCALL>[...]</TOOLCALL>
     * (?s) makes dot match newlines for multi-line blocks.
     */
    private static final Pattern TOOLCALL_XML_BLOCK = Pattern.compile("(?s)<TOOLCALL>.*?</TOOLCALL>");

    /**
     * Bare JSON tool-call array that IS the entire answer string.
     * e.g. [{"name":"schedule_task","arguments":{...}}]
     * Only matches when the whole string looks like a tool array.
     */
    private static final Pattern BARE_TOOL_JSON_ARRAY =
            Pattern.compile("(?s)^\\s*\\[\\s*\\{\\s*\"name\"\\s*:.*?\"arguments\"\\s*:.*}\\s*]\\s*$");

    /**
     * Leftover fragment from a split TOOLCALL block.
     * Catches: ": "list"}}]</TOOLCALL>  or  OLCALL>[...]
     */
    private static final Pattern TOOLCALL_FRAGMENT = Pattern.compile("(?s)(OLCALL>|</TOOLCALL>|<TOOLCALL).*");

    public static String format(AgentResult result) {
        return format(result, false);
    }

    public static String formatBasic(AgentResult result) {
        return format(result, true);
    }

    private static String format(AgentResult result, boolean basic) {
        String reply =
                switch (result) {
                    case AgentResult.Finished f -> sanitize(f.answer());
                    case AgentResult.StructuredFinished sf -> sanitize(sf.answer());
                    case AgentResult.AwaitingApproval a -> {
                        boolean isCron =
                                a.sessionId() != null && a.sessionId().startsWith(AgentSentinels.CRON_SESSION_PREFIX);
                        if (basic) {
                            yield formatBasicHitlMessage(a.toolName(), a.sessionId(), isCron);
                        }
                        yield formatHitlMessage(a.toolName(), a.paramsJson(), a.sessionId(), isCron);
                    }
                    case AgentResult.LoopExceeded _ -> "⏱ I ran out of steps. Try rephrasing your request.";
                    case AgentResult.AwaitingRetry r ->
                        "⏳ Rate limit hit. Automatically retrying in " + (r.retryAfterMs() / 1000) + "s...";
                    case AgentResult.Failed f -> {
                        String msg = AgentErrorMessageUtil.sanitizeErrorMessage(f.reason());
                        yield (f.category() == AgentResult.FailureCategory.RATE_LIMIT || msg.startsWith("⏳"))
                                ? msg
                                : "❌ " + msg;
                    }
                };

        if (reply == null || reply.isBlank()) {
            reply = "✅ Done.";
        }
        return reply;
    }

    /**
     * Strips raw TOOLCALL markup that LLMs occasionally embed in FINISH answers.
     * Safe to call with null or blank - returns as-is.
     * Order matters: strip full blocks first, then bare arrays, then leftover fragments.
     */
    static String sanitize(String text) {
        if (text == null || text.isBlank()) return text;

        String cleaned = text;

        // 1. Full XML block:  <TOOLCALL>[...]</TOOLCALL>
        cleaned = TOOLCALL_XML_BLOCK.matcher(cleaned).replaceAll("").strip();

        // 2. Bare JSON tool-array that IS the entire answer string
        if (BARE_TOOL_JSON_ARRAY.matcher(cleaned).matches()) {
            return "[Tool output was malformed - no answer text was generated.]";
        }

        // 3. Trailing/leading fragment from a split TOOLCALL block
        cleaned = TOOLCALL_FRAGMENT.matcher(cleaned).replaceAll("").strip();

        return cleaned.isBlank() ? "[Tool output was malformed - no answer text was generated.]" : cleaned;
    }

    /**
     * Specialized format for first-time Cron HITL prompts.
     */
    public static String formatCronHitl(String cronId, String goal, String toolName) {
        return "⚠️ Approval Required for background task.\n\n" + "Goal: " + goal + "\n\n"
                + formatHitlMessage(toolName, null, cronId, true);
    }

    private static String formatBasicHitlMessage(String compositeName, String cronId, boolean isCron) {
        StringBuilder sb = new StringBuilder();
        boolean isBatch =
                compositeName != null && compositeName.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX);

        if (isBatch) {
            sb.append("⏸ Parallel batch execution requires approval.");
        } else {
            sb.append("⏸ Approval needed for tool: ").append(compositeName);
        }
        return sb.toString();
    }

    private static String formatHitlMessage(String compositeName, String paramsJson, String cronId, boolean isCron) {
        StringBuilder sb = new StringBuilder();

        boolean isBatch =
                compositeName != null && compositeName.startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX);

        if (isBatch) {
            String[] tools = compositeName
                    .substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length())
                    .split(",");
            sb.append("⏸ Parallel batch execution requires approval.\n\n");
            sb.append("Tools pending:\n");

            // Count occurrences to group duplicates
            Map<String, Integer> toolCounts = new LinkedHashMap<>();
            for (String ts : tools) {
                toolCounts.put(ts, toolCounts.getOrDefault(ts, 0) + 1);
            }

            for (Map.Entry<String, Integer> entry : toolCounts.entrySet()) {
                sb.append("• ").append(entry.getKey());
                if (entry.getValue() > 1) {
                    sb.append(" (").append(entry.getValue()).append(" calls)");
                }
                sb.append("\n");
            }
        } else {
            sb.append("⏸ Approval needed for tool: ").append(compositeName);
            if (paramsJson != null && !paramsJson.isBlank()) {
                String display =
                        paramsJson.length() > 500 ? paramsJson.substring(0, 500) + "\n… [truncated]" : paramsJson;
                sb.append("\n\n📋 Params:\n").append(display);
            }
        }

        sb.append("\n\n");

        String cronPrefix = isCron && cronId != null ? " " + cronId : "";
        if (isBatch) {
            sb.append("To approve/deny specific tools:\n")
                    .append("  /approve")
                    .append(cronPrefix)
                    .append(" <toolName>\n")
                    .append("  /deny")
                    .append(cronPrefix)
                    .append(" <toolName>\n\n")
                    .append("Or decide all at once:\n")
                    .append("  /approve")
                    .append(cronPrefix)
                    .append(" or /deny")
                    .append(cronPrefix)
                    .append(" (approve/deny all)");
        } else {
            sb.append("Reply /approve")
                    .append(cronPrefix)
                    .append(" or /deny")
                    .append(cronPrefix)
                    .append(" (confirm/cancel)");
        }

        return sb.toString();
    }
}
