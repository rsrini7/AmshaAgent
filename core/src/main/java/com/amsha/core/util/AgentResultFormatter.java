package com.amsha.core.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.message.UserMessages;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Shared logic for rendering {@link AgentResult} to user-friendly text.
 * Unified across interactive sessions and background cron tasks.
 *
 * Added sanitize() to strip raw TOOLCALL markup that OpenRouter free-tier
 * models occasionally embed inside FINISH answer text. Groq never did this because
 * it followed the format schema strictly; OpenRouter free models do not.
 */
public class AgentResultFormatter {
    private AgentResultFormatter() {
        /* This utility class should not be instantiated */
    }

    private static final String MALFORMED_TOOL_OUTPUT = "[Tool output was malformed - no answer text was generated.]";
    private static final String RECOVERED_OBSERVATION_MAX_CHARS_PROP = "amsha.chat.recovered-observation-max-chars";
    private static final int MAX_RECOVERED_OBSERVATION_CHARS = 2_000;

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

    /**
     * JSON wrapper that some LLMs emit around plain-text answers.
     * e.g. {"response":"The answer is 42"}
     */
    private static final Pattern RESPONSE_JSON_WRAPPER =
            Pattern.compile("^\\s*\\{\\s*\"response\"\\s*:\\s*\"(.*)\"\\s*\\}\\s*$", Pattern.DOTALL);

    public static String format(AgentResult result) {
        return format(result, false);
    }

    public static String formatBasic(AgentResult result) {
        return format(result, true);
    }

    private static String format(AgentResult result, boolean basic) {
        String reply =
                switch (result) {
                    case AgentResult.Finished f -> recoverMalformedToolOutput(sanitize(f.answer()), f.steps());
                    case AgentResult.StructuredFinished sf ->
                        recoverMalformedToolOutput(sanitize(sf.answer()), sf.steps());
                    case AgentResult.AwaitingApproval a -> {
                        boolean isCron =
                                a.sessionId() != null && a.sessionId().startsWith(AgentSentinels.CRON_SESSION_PREFIX);
                        if (basic) {
                            yield formatBasicHitlMessage(a.toolName(), a.sessionId(), isCron);
                        }
                        yield formatHitlMessage(a.toolName(), a.displayParamsJson(), a.sessionId(), isCron);
                    }
                    case AgentResult.LoopExceeded _ -> "⏱ I ran out of steps. Try rephrasing your request.";
                    case AgentResult.AwaitingRetry r ->
                        "⏳ Rate limit hit. Automatically retrying in " + (r.retryAfterMs() / 1000) + "s...";
                    case AgentResult.Failed f -> {
                        String msg = AgentErrorMessageUtil.sanitizeErrorMessage(f.reason());
                        msg = UserMessages.stripInternalDirectiveTail(msg);
                        yield (f.category() == AgentResult.FailureCategory.RATE_LIMIT || msg.startsWith("⏳"))
                                ? msg
                                : "❌ " + msg;
                    }
                };

        String safeReply = UserMessages.stripInternalDirectiveTail(reply);
        return safeReply == null || safeReply.isBlank() ? "✅ Done." : safeReply;
    }

    private static String recoverMalformedToolOutput(String formattedAnswer, List<ReasoningStep> steps) {
        if (!MALFORMED_TOOL_OUTPUT.equals(formattedAnswer)) {
            return formattedAnswer;
        }
        if (steps == null || steps.isEmpty()) {
            return UserMessages.AI_UNEXPECTED_RESPONSE;
        }

        return steps.reversed().stream()
                .filter(step -> !ReasoningStep.OBSERVE.equals(step.action()))
                .filter(step -> !ReasoningStep.FINISH.equals(step.action()))
                .filter(step -> Boolean.TRUE.equals(step.success()))
                .map(ReasoningStep::observation)
                .filter(obs -> obs != null && !obs.isBlank())
                .findFirst()
                .map(AgentResultFormatter::formatRecoveredObservation)
                .orElse(UserMessages.AI_UNEXPECTED_RESPONSE);
    }

    private static String formatRecoveredObservation(String observation) {
        String safeObservation = UserMessages.stripInternalDirectiveTail(observation);
        String trimmed = safeObservation == null ? "" : safeObservation.strip();
        String jsonSummary = summarizeJsonObservation(trimmed);
        if (jsonSummary != null && !jsonSummary.isBlank()) {
            return jsonSummary;
        }
        int maxChars = ConfigProvider.getConfig()
                .getOptionalValue(RECOVERED_OBSERVATION_MAX_CHARS_PROP, Integer.class)
                .orElse(MAX_RECOVERED_OBSERVATION_CHARS);
        if (trimmed.length() > maxChars) {
            return trimmed.substring(0, Math.max(0, maxChars)).stripTrailing() + "...";
        }
        return trimmed;
    }

    private static String summarizeJsonObservation(String observation) {
        try {
            JsonNode root = new ObjectMapper().readTree(observation);
            if (root.isArray()) {
                return summarizeJsonArray(root);
            }
            if (root.isObject()) {
                return summarizeJsonObject(root);
            }
        } catch (Exception _) {
            return null;
        }
        return null;
    }

    private static String summarizeJsonArray(JsonNode root) {
        if (root.isEmpty()) {
            return "Tool completed successfully. No records were returned.";
        }

        StringBuilder sb = new StringBuilder("Tool completed successfully.\n\n");
        int count = Math.min(root.size(), 5);
        for (int i = 0; i < count; i++) {
            JsonNode item = root.get(i);
            sb.append(i + 1).append(". ").append(titleForJsonItem(item)).append("\n");
            appendField(sb, item, "description", "Description");
            appendField(sb, item, "created_at", "Created");
            appendFiles(sb, item);
        }
        return sb.toString().stripTrailing();
    }

    private static String summarizeJsonObject(JsonNode root) {
        StringBuilder sb = new StringBuilder("Tool completed successfully.");
        String title = titleForJsonItem(root);
        if (!title.isBlank()) {
            sb.append("\n\n").append(title);
        }
        appendField(sb, root, "description", "Description");
        appendField(sb, root, "created_at", "Created");
        appendFiles(sb, root);
        return sb.toString().stripTrailing();
    }

    private static String titleForJsonItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            return "Result";
        }
        for (String field : List.of("title", "name", "description", "id")) {
            JsonNode value = item.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        JsonNode files = item.get("files");
        if (files != null && files.isArray() && !files.isEmpty()) {
            JsonNode first = files.get(0);
            if (first != null && first.isTextual() && !first.asText().isBlank()) {
                return first.asText();
            }
        }
        return "Result";
    }

    private static void appendField(StringBuilder sb, JsonNode item, String field, String label) {
        if (item == null || !item.isObject()) {
            return;
        }
        JsonNode value = item.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            sb.append("   ").append(label).append(": ").append(value.asText()).append("\n");
        }
    }

    private static void appendFiles(StringBuilder sb, JsonNode item) {
        if (item == null || !item.isObject()) {
            return;
        }
        JsonNode files = item.get("files");
        if (files == null || !files.isArray() || files.isEmpty()) {
            return;
        }
        String joined = "";
        for (JsonNode file : files) {
            if (file != null && file.isTextual() && !file.asText().isBlank()) {
                joined = joined.isBlank() ? file.asText() : joined + ", " + file.asText();
            }
        }
        if (!joined.isBlank()) {
            sb.append("   Files: ").append(joined).append("\n");
        }
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
            return MALFORMED_TOOL_OUTPUT;
        }

        // 2b. JSON wrapper {"response":"..."} — unwrap to plain text
        var responseWrapper = RESPONSE_JSON_WRAPPER.matcher(cleaned);
        if (responseWrapper.matches()) {
            String unwrapped = responseWrapper.group(1).replace("\\\"", "\"").strip();
            return unwrapped.isBlank() ? MALFORMED_TOOL_OUTPUT : unwrapped;
        }

        // 3. Trailing/leading fragment from a split TOOLCALL block
        cleaned = TOOLCALL_FRAGMENT.matcher(cleaned).replaceAll("").strip();

        // 4. Trailing DSML-style tool-call block
        cleaned = stripDsmlLeakBlock(cleaned);

        // 4b. Generic XML-style tool-call block
        cleaned = stripXmlToolCallBlock(cleaned);

        // 5. Full HTML artifact: collapse to a short browser-preview notice so
        // Telegram does not dump the generated page body as chat text.
        cleaned = HtmlArtifactUtil.collapseHtmlArtifact(cleaned);

        // Delegation and orchestration adapters may append coordinator-only
        // instructions after producing an otherwise valid answer. They are
        // model-facing control data and must never cross a user-facing path.
        cleaned = UserMessages.stripInternalDirectiveTail(cleaned);

        return cleaned.isBlank() ? MALFORMED_TOOL_OUTPUT : cleaned;
    }

    private static String stripDsmlLeakBlock(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        int dsmlIdx = indexOfIgnoreCase(text, "DSML");
        if (dsmlIdx < 0) {
            return text;
        }

        int lineStart = text.lastIndexOf('\n', dsmlIdx);
        if (lineStart < 0) {
            return text.substring(0, dsmlIdx).stripTrailing();
        }
        return text.substring(0, lineStart).stripTrailing();
    }

    private static String stripXmlToolCallBlock(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        int toolCallIdx = firstIndexOfIgnoreCase(text, "<tool_call");
        int functionIdx = firstIndexOfIgnoreCase(text, "<function=");
        int idx = minPositive(toolCallIdx, functionIdx);
        if (idx < 0) {
            return text;
        }

        int lineStart = text.lastIndexOf('\n', idx);
        if (lineStart < 0) {
            return text.substring(0, idx).stripTrailing();
        }
        return text.substring(0, lineStart).stripTrailing();
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static int firstIndexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static int minPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /**
     * Specialized format for first-time Cron HITL prompts.
     */
    public static String formatCronHitl(String cronId, String goal, String toolName) {
        return formatCronHitl(cronId, goal, toolName, null);
    }

    /**
     * Specialized format for Cron HITL prompts with tool parameters.
     */
    public static String formatCronHitl(String cronId, String goal, String toolName, String paramsJson) {
        String header = (goal != null && !goal.isBlank())
                ? "⚠️ Approval Required for background task.\n\nGoal: " + goal + "\n\n"
                : "⚠️ Approval Required for background task.\n\n";
        return header + formatHitlMessage(toolName, paramsJson, cronId, true);
    }

    private static String formatBasicHitlMessage(String compositeName, String _cronId, boolean _isCron) {
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
                    .split(",", -1);
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
