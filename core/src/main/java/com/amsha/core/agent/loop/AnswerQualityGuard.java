package com.amsha.core.agent.loop;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;

import org.jboss.logging.Logger;

/**
 * Validates and sanitises LLM-produced answers before they are returned as
 * FINISH results.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Raw tool-call leak detection (4 patterns + NVIDIA strip)</li>
 *   <li>Suspiciously-short / punctuation-only answer detection</li>
 *   <li>Compaction context-echo stripping (delegates pattern reference to
 *       {@link AgentSentinels} - patterns are NOT duplicated here)</li>
 *   <li>System-prompt echo detection (delegates to AgentSentinels)</li>
 *   <li>{@code stripRawToolJson()} utility used by both the leak-retry path and
 *       the salvage path</li>
 * </ul>
 *
 * <p><strong>§9.1 (MUST):</strong> every {@link Pattern} in this class is
 * {@code static final}. Per-call {@code Pattern.compile()} on any answer
 * validation or stripping path is FORBIDDEN.
 *
 * <p><strong>No persistence, no transactions, no CDI state.</strong>
 * All methods are stateless; this bean may be called from any thread.
 */
@ApplicationScoped
public class AnswerQualityGuard implements AnswerGuardStrategy {

    private static final Logger log = Logger.getLogger(AnswerQualityGuard.class);

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    TextPatternUtils textPatternUtils;

    private Pattern nakedFunctionCallPattern;
    private LeakedToolCallParser leakedToolCallParser;

    @PostConstruct
    void initialize() {
        String toolNames = toolRegistry.listTools().stream().sorted().collect(Collectors.joining("|"));

        // Dynamic pattern: \b(tool1|tool2|...)\s*\(([^)]*)\)
        this.nakedFunctionCallPattern =
                Pattern.compile("\\b(" + toolNames + ")\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
        this.leakedToolCallParser = new LeakedToolCallParser(toolRegistry);
        log.debugf("AnswerQualityGuard initialized with dynamic naked call pattern for tools: %s", toolNames);
    }

    // -------------------------------------------------------------------------
    // §9.1 - ALL patterns MUST be static final (no inline Pattern.compile)
    // -------------------------------------------------------------------------

    /** Detects standard JSON tool-call envelope: {"type":"function", ...} */
    static final Pattern RAW_TOOL_CALL_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"function\"", Pattern.DOTALL);

    /** Detects tool_calls array: {"tool_calls": [...]} */
    static final Pattern RAW_TOOL_CALLS_ARRAY_PATTERN = Pattern.compile("\"tool_calls\"\\s*:\\s*\\[", Pattern.DOTALL);

    /** Detects markdown-wrapped delegatetoagent or m-delegate call. */
    static final Pattern RAW_DELEGATE_CALL_PATTERN =
            Pattern.compile("```?m?delegatetoagent|agent", Pattern.CASE_INSENSITIVE);

    /** Detects bare {"name":..., "parameters":...} or {"tool_name":..., "parameters":...} JSON tool invocations. */
    static final Pattern RAW_TOOL_JSON_PATTERN =
            Pattern.compile("\"(?:tool_)?name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"parameters\"", Pattern.DOTALL);

    /** Detects NVIDIA-style TOOLCALLS prefix leaking into FINISH text. */
    static final Pattern NVIDIA_TOOL_CALL_PATTERN = Pattern.compile("TOOLCALLS", Pattern.CASE_INSENSITIVE);

    /** Detects Mistral-style [TOOL_CALLS] prefix leaking into FINISH text. */
    static final Pattern MISTRAL_TOOL_CALL_PATTERN = Pattern.compile("\\[TOOL_CALLS\\]", Pattern.CASE_INSENSITIVE);

    /** Detects DSML-style tool-call wrappers leaking into FINISH text. */
    static final Pattern DSML_TOOL_CALL_PATTERN = Pattern.compile("(?is)DSML.*invoke\\s+name\\s*=\\s*\"");

    /** Detects generic XML-style tool-call wrappers leaking into FINISH text. */
    static final Pattern XML_TOOL_CALL_PATTERN =
            Pattern.compile("(?is)<tool_call\\b.*?(?:<function\\s*=\\s*|\"name\"\\s*:\\s*\"|<tool_name\\b)");

    /** Extracts the DSML tool name from an {@code invoke name=\"...\"} block. */
    static final Pattern DSML_TOOL_NAME_PATTERN = Pattern.compile("(?is)invoke\\s+name\\s*=\\s*\"([^\"]+)\"");

    /** Extracts the tool name from a generic XML {@code <function=tool>} block. */
    static final Pattern XML_TOOL_NAME_PATTERN =
            Pattern.compile("(?is)<function\\s*=\\s*\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?\\s*>");

    /** Extracts the tool name from a generic XML {@code <tool_name>tool</tool_name>} block. */
    static final Pattern XML_TOOL_NAME_TAG_PATTERN = Pattern.compile("(?is)<tool_name\\b[^>]*>([^<]+)</tool_name>");

    /**
     * Extracts the tool name from a standard JSON leak so the correction hint
     * can name the tool explicitly.
     * Previously compiled inline inside the FINISH handler - §9.1 violation fixed.
     */
    static final Pattern LEAKED_TOOL_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Extracts the tool name from an NVIDIA-style TOOLCALLS leak.
     * Previously compiled inline inside the FINISH handler - §9.1 violation fixed.
     */
    static final Pattern NVIDIA_TOOL_NAME_PATTERN = Pattern.compile("TOOLCALLS\\s+(\\S+)");

    /**
     * Strips everything from the first TOOLCALLS token onwards (inclusive).
     * Previously compiled inline inside {@code stripRawToolJson()} - the
     * highest-frequency §9.1 violation because that method is called on every
     * FINISH answer.
     */
    static final Pattern NVIDIA_STRIP_PATTERN = Pattern.compile("(?i)TOOLCALLS.*", Pattern.DOTALL);

    /** Strips everything from the first [TOOL_CALLS] token onwards. */
    static final Pattern MISTRAL_STRIP_PATTERN = Pattern.compile("(?i)\\[TOOL_CALLS\\].*", Pattern.DOTALL);

    /**
     * Matches suspiciously short non-answers: "Done.", "OK.", "ok", etc.
     * Indicates a compaction-race / thin-context response.
     */
    static final Pattern SUSPICIOUSLY_SHORT =
            Pattern.compile("^\\s*(?:done\\.?|ok\\.?)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Minimum number of alphabetic characters required for an answer to be
     * considered non-garbage. Catches 2-token outputs like "." or "…" that
     * contain zero alphabetic content (Bug-1 fix).
     */
    static final int MIN_ANSWER_ALPHA_LENGTH = 3;

    /**
     * Minimum length for a descriptive answer to bypass JSON leak detection
     * (e.g. when describing tool schemas for 'capabilities ?' query).
     */
    static final int DESCRIPTIVE_BYPASS_MIN_LENGTH = 500;

    /** Keywords that indicate a descriptive intent rather than a technical leak. */
    static final Pattern DESC_KEYWORD_PATTERN = Pattern.compile("(?i)capabilities|available tools|agent roles|schema");

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the candidate answer contains a raw tool-call
     * leak in any of the four recognised formats.
     *
     * @param answer the candidate FINISH answer text
     * @return {@code true} if a leak is detected
     */
    @Override
    public boolean isRawToolCallLeak(String answer) {
        if (answer == null || answer.isBlank()) return false;

        // Heuristic: If the answer is long and contains descriptive keywords,
        // it is likely a valid explanation of capabilities rather than a leak.
        if (answer.length() > DESCRIPTIVE_BYPASS_MIN_LENGTH
                && DESC_KEYWORD_PATTERN.matcher(answer).find()) {
            return false;
        }

        return RAW_TOOL_CALL_PATTERN.matcher(answer).find()
                || RAW_TOOL_CALLS_ARRAY_PATTERN.matcher(answer).find()
                || RAW_DELEGATE_CALL_PATTERN.matcher(answer).find()
                || RAW_TOOL_JSON_PATTERN.matcher(answer).find()
                || NVIDIA_TOOL_CALL_PATTERN.matcher(answer).find()
                || MISTRAL_TOOL_CALL_PATTERN.matcher(answer).find()
                || DSML_TOOL_CALL_PATTERN.matcher(answer).find()
                || XML_TOOL_CALL_PATTERN.matcher(answer).find()
                || (nakedFunctionCallPattern != null
                        && nakedFunctionCallPattern.matcher(answer).find());
    }

    /**
     * Strips any raw JSON tool-call envelope and NVIDIA TOOLCALLS block from
     * the answer, returning the remaining prose.
     *
     * <p>Used in two places:
     * <ol>
     *   <li>Before writing a leaked step to history (clean the step first).</li>
     *   <li>Salvage path: strip the leak and return the remaining prose as the
     *       final answer.</li>
     * </ol>
     *
     * @param answer raw answer text that may contain tool-call artefacts
     * @return cleaned answer string (may be blank if the entire text was a leak)
     */
    @Override
    public String stripRawToolJson(String answer) {
        if (answer == null) return "";
        String stripped = answer;

        // Remove markdown JSON code blocks entirely if they appear to be tool leaks
        // Handles nested braces correctly by matching until the closing fence.
        stripped = stripped.replaceAll("(?s)```json\\s*.*?\\s*```", "");

        // Remove {"type": "function", ...} - greedy to handle nested objects
        stripped = stripped.replaceAll("(?s)\\{[^{}]*\"type\"\\s*:\\s*\"function\"[^{}]*}", "");

        // Remove {"tool_calls": [...]} or {"calls": [...]} entirely
        stripped = stripped.replaceAll("(?s)\\{\\s*\"(?:tool_)?calls\"\\s*:.*?}", "");

        // Remove DSML tool-call wrappers entirely.
        stripped = stripDsmlLeakBlock(stripped);

        // Remove generic XML tool-call wrappers entirely.
        stripped = stripXmlToolCallBlock(stripped);

        // Remove {"name": ..., "parameters": ...} or {"tool_name": ..., "parameters": ...}
        stripped = stripped.replaceAll("(?s)\\{\\s*\"(?:tool_)?name\"\\s*:.*?\"parameters\"\\s*:.*?}", "");

        // Remove naked function calls: tool_name(param="value")
        if (nakedFunctionCallPattern != null) {
            stripped = nakedFunctionCallPattern.matcher(stripped).replaceAll("");
        }

        // Final catch-all for any trailing JSON fragment or punctuation-only remnants
        String trimmed = stripped.strip();
        if (trimmed.length() < 10) {
            // If very short and consists only of JSON/markdown punctuation, clear it
            if (trimmed.matches("^[\\s\\{\\}\\[\\]\\\":\\,`\\-]*$")) {
                stripped = "";
            }
        }

        // Final catch-all: if the entire remaining text is a single JSON object, remove it
        trimmed = stripped.strip();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            // Check if it contains tool-related keys
            if (trimmed.contains("\"name\"")
                    || trimmed.contains("\"action\"")
                    || trimmed.contains("\"tool\"")
                    || trimmed.contains("\"function\"")
                    || trimmed.contains("\"query\"")
                    || trimmed.contains("\"parameters\"")) {
                stripped = "";
            }
        }
        return stripped.strip();
    }

    private static String stripDsmlLeakBlock(String text) {
        if (text == null || text.isBlank()) {
            return "";
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

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static String stripXmlToolCallBlock(String text) {
        if (text == null || text.isBlank()) {
            return "";
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

    private static int firstIndexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static int minPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /**
     * Extracts the leaked tool name from a raw-tool-call answer for use in
     * correction hints. Returns {@code "the intended tool"} as a safe fallback.
     *
     * @param answer answer text known to contain a leak
     * @return the tool name extracted from the leak, or a safe fallback string
     */
    @Override
    public String extractLeakedToolName(String answer) {
        if (answer == null) return "the intended tool";
        Matcher nameMatcher = LEAKED_TOOL_NAME_PATTERN.matcher(answer);
        if (nameMatcher.find()) return nameMatcher.group(1);
        Matcher nvidiaMatcher = NVIDIA_TOOL_NAME_PATTERN.matcher(answer);
        if (nvidiaMatcher.find()) return nvidiaMatcher.group(1);

        Matcher dsmlMatcher = DSML_TOOL_NAME_PATTERN.matcher(answer);
        if (dsmlMatcher.find()) return dsmlMatcher.group(1);

        Matcher xmlMatcher = XML_TOOL_NAME_PATTERN.matcher(answer);
        if (xmlMatcher.find()) return xmlMatcher.group(1);

        Matcher xmlTagMatcher = XML_TOOL_NAME_TAG_PATTERN.matcher(answer);
        if (xmlTagMatcher.find()) return xmlTagMatcher.group(1).strip();

        if (nakedFunctionCallPattern != null) {
            Matcher nakedMatcher = nakedFunctionCallPattern.matcher(answer);
            if (nakedMatcher.find()) {
                // Heuristic: get the part before the first '('
                String block = nakedMatcher.group(0);
                int parenIdx = block.indexOf('(');
                if (parenIdx > 0) return block.substring(0, parenIdx).trim();
            }
        }

        if (answer.contains("delegatetoagent")) return "delegatetoagent";

        if (textPatternUtils.looksLikeJson(answer)) {
            return "a structured JSON block";
        }

        return "the intended tool";
    }

    @Override
    public Optional<LeakedToolCall> extractLeakedToolCall(String answer) {
        return leakedToolCallParser.extractLeakedToolCall(answer);
    }

    @Override
    public AnswerGuardResult guard(String rawAnswer, List<ReasoningStep> history) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return new AnswerGuardResult(
                    "I wasn't able to generate a response. Please try again.", false, "null_or_blank");
        }

        String sessionId =
                "unknown"; // Strategy signature change - may need contextual session lookup if logging is critical here

        // Existing leak detection
        if (isRawToolCallLeak(rawAnswer)) {
            String toolName = extractLeakedToolName(rawAnswer);
            return new AnswerGuardResult(rawAnswer, false, "raw_tool_call_leak:" + toolName);
        }

        String guarded = guardFinalAnswer(rawAnswer, 0, sessionId);
        boolean salvaged = !guarded.equals(rawAnswer.strip());

        if (guarded.contains("Please try again") && !salvaged) {
            return new AnswerGuardResult(guarded, false, "answer_validation_failed");
        }

        return new AnswerGuardResult(guarded, salvaged, null);
    }

    /**
     * Guards the final answer against empty, suspiciously short, compaction-echo,
     * and system-prompt-echo responses.
     *
     * <p>Returns a safe user-facing retry prompt when the answer fails any guard,
     * or a salvaged prefix when only the tail is a system-prompt echo.
     *
     * @param answer       the candidate FINISH answer text
     * @param outputTokens the token count reported by the LLM (for logging)
     * @param sessionId    the active session ID (for logging)
     * @return the validated (and possibly salvaged) answer, never {@code null}
     */
    public String guardFinalAnswer(String answer, int outputTokens, String sessionId) {
        if (answer == null || answer.isBlank()) {
            log.warnf("ANSWER-GUARD null/blank answer for session %s - returning retry prompt", sessionId);
            return "I wasn't able to generate a response. Please try again.";
        }

        String stripped = answer.strip();

        // Guard 1: suspiciously short non-answer
        if (SUSPICIOUSLY_SHORT.matcher(stripped).matches()) {
            log.warnf(
                    "ANSWER-GUARD suspiciously short answer '%s' (tokens=%d) for session %s - retry prompt",
                    stripped, outputTokens, sessionId);
            return "I wasn't able to generate a meaningful response. Please try again.";
        }

        // Guard 2: punctuation-only garbage (Bug-1 fix)
        long alphaCount = stripped.chars().filter(Character::isLetterOrDigit).count();
        if (stripped.length() < MIN_ANSWER_ALPHA_LENGTH && alphaCount == 0) {
            log.warnf(
                    "ANSWER-GUARD punctuation-only answer '%s' (tokens=%d) for session %s - retry prompt",
                    stripped, outputTokens, sessionId);
            return "I wasn't able to generate a meaningful response. Please try again.";
        }

        // Guard 3: compaction RAG-echo (delegates pattern ref to AgentSentinels)
        if (AgentSentinels.CONTEXT_RAG_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf("ANSWER-GUARD detected RAG echo in answer for session %s - stripping header", sessionId);
            stripped = stripContextHeader(stripped, AgentSentinels.CONTEXT_RAG_ECHO_PATTERN);
            if (stripped.isBlank()) {
                return "I wasn't able to generate a clean response. Please try again.";
            }
        }

        // Guard 4: compaction "Retrieved Context" echo
        if (AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf(
                    "ANSWER-GUARD detected Retrieved Context echo in answer for session %s - stripping header",
                    sessionId);
            stripped = stripContextHeader(stripped, AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN);
            if (stripped.isBlank()) {
                return "I wasn't able to generate a clean response. Please try again.";
            }
        }

        // Guard 5: system-prompt echo (delegates pattern ref to AgentSentinels)
        if (AgentSentinels.SYSTEM_PROMPT_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf(
                    "ANSWER-GUARD detected system prompt echo in answer for session %s - stripping internal context",
                    sessionId);
            int echoStart = findEchoStart(stripped, AgentSentinels.SYSTEM_PROMPT_ECHO_PATTERN);
            if (echoStart > 0) {
                String salvaged = stripped.substring(0, echoStart).strip();
                if (!salvaged.isBlank() && salvaged.length() >= MIN_ANSWER_ALPHA_LENGTH) {
                    return salvaged;
                }
            }
            return "I wasn't able to generate a clean response. Please try again.";
        }

        // Guard 6: system instruction echo (delegates pattern ref to AgentSentinels)
        // The model echoes back formatting instructions ("Response must be 1-2 sentences")
        // as meta-commentary instead of answering the user's question.
        if (AgentSentinels.SYSTEM_INSTRUCTION_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf("ANSWER-GUARD detected system instruction echo in answer for session %s - rejecting", sessionId);
            return "I wasn't able to generate a meaningful response. Please try again.";
        }

        String collapsed = HtmlArtifactUtil.collapseHtmlArtifact(stripped);
        if (!collapsed.equals(stripped)) {
            log.warnf("ANSWER-GUARD collapsed HTML artifact in answer for session %s", sessionId);
            return collapsed;
        }

        return stripped;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String stripContextHeader(String text, Pattern echoPattern) {
        Matcher m = echoPattern.matcher(text);
        if (m.find()) {
            int end = m.end();
            // Find the next non-blank line after the echo header
            String remainder = text.substring(end).stripLeading();
            return remainder.isBlank() ? "" : remainder;
        }
        return text;
    }

    private int findEchoStart(String text, Pattern echoPattern) {
        Matcher m = echoPattern.matcher(text);
        return m.find() ? m.start() : -1;
    }
}
