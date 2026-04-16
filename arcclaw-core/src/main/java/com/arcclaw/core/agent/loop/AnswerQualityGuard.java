package com.arcclaw.core.agent.loop;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.AgentSentinels;
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
 *       {@link AgentSentinels} — patterns are NOT duplicated here)</li>
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
public class AnswerQualityGuard {

    private static final Logger log = Logger.getLogger(AnswerQualityGuard.class);

    // -------------------------------------------------------------------------
    // §9.1 — ALL patterns MUST be static final (no inline Pattern.compile)
    // -------------------------------------------------------------------------

    /** Detects standard JSON tool-call envelope: {"type":"function", ...} */
    static final Pattern RAW_TOOL_CALL_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*\"function\"", Pattern.DOTALL);

    /** Detects markdown-wrapped delegatetoagent or m-delegate call. */
    static final Pattern RAW_DELEGATE_CALL_PATTERN =
            Pattern.compile("```?m?delegatetoagent|agent", Pattern.CASE_INSENSITIVE);

    /** Detects bare {"name":..., "parameters":...} JSON tool invocations. */
    static final Pattern RAW_TOOL_JSON_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"parameters\"", Pattern.DOTALL);

    /** Detects NVIDIA-style TOOLCALLS prefix leaking into FINISH text. */
    static final Pattern NVIDIA_TOOL_CALL_PATTERN =
            Pattern.compile("TOOLCALLS", Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the tool name from a standard JSON leak so the correction hint
     * can name the tool explicitly.
     * Previously compiled inline inside the FINISH handler — §9.1 violation fixed.
     */
    static final Pattern LEAKED_TOOL_NAME_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Extracts the tool name from an NVIDIA-style TOOLCALLS leak.
     * Previously compiled inline inside the FINISH handler — §9.1 violation fixed.
     */
    static final Pattern NVIDIA_TOOL_NAME_PATTERN =
            Pattern.compile("TOOLCALLS\\s+(\\S+)");

    /**
     * Strips everything from the first TOOLCALLS token onwards (inclusive).
     * Previously compiled inline inside {@code stripRawToolJson()} — the
     * highest-frequency §9.1 violation because that method is called on every
     * FINISH answer.
     */
    static final Pattern NVIDIA_STRIP_PATTERN =
            Pattern.compile("(?i)TOOLCALLS.*", Pattern.DOTALL);

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
    public boolean isRawToolCallLeak(String answer) {
        if (answer == null || answer.isBlank()) return false;
        return RAW_TOOL_CALL_PATTERN.matcher(answer).find()
                || RAW_DELEGATE_CALL_PATTERN.matcher(answer).find()
                || RAW_TOOL_JSON_PATTERN.matcher(answer).find()
                || NVIDIA_TOOL_CALL_PATTERN.matcher(answer).find();
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
    public String stripRawToolJson(String answer) {
        if (answer == null) return "";
        String stripped = answer;
        // Remove JSON tool-call blocks: {...} or ```json {...}
        stripped = stripped.replaceAll("(?s)```json\\s*\\{.*?}\\s*```", "");
        stripped = stripped.replaceAll("(?s)\\{[^{}]*\"type\"\\s*:\\s*\"function\"[^{}]*}", "");
        stripped = stripped.replaceAll("(?s)\\{[^{}]*\"name\"[^{}]*\"parameters\"[^{}]*}", "");
        // §9.1 fix: was compiled inline — now uses static final NVIDIA_STRIP_PATTERN
        stripped = NVIDIA_STRIP_PATTERN.matcher(stripped).replaceAll("");
        return stripped.strip();
    }

    /**
     * Extracts the leaked tool name from a raw-tool-call answer for use in
     * correction hints. Returns {@code "the intended tool"} as a safe fallback.
     *
     * @param answer answer text known to contain a leak
     * @return the tool name extracted from the leak, or a safe fallback string
     */
    public String extractLeakedToolName(String answer) {
        if (answer == null) return "the intended tool";
        Matcher nameMatcher = LEAKED_TOOL_NAME_PATTERN.matcher(answer);
        if (nameMatcher.find()) return nameMatcher.group(1);
        Matcher nvidiaMatcher = NVIDIA_TOOL_NAME_PATTERN.matcher(answer);
        if (nvidiaMatcher.find()) return nvidiaMatcher.group(1);
        if (answer.contains("delegatetoagent")) return "delegatetoagent";
        return "the intended tool";
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
            log.warnf("ANSWER-GUARD null/blank answer for session %s — returning retry prompt", sessionId);
            return "I wasn't able to generate a response. Please try again.";
        }

        String stripped = answer.strip();

        // Guard 1: suspiciously short non-answer
        if (SUSPICIOUSLY_SHORT.matcher(stripped).matches()) {
            log.warnf("ANSWER-GUARD suspiciously short answer '%s' (tokens=%d) for session %s — retry prompt",
                    stripped, outputTokens, sessionId);
            return "I wasn't able to generate a meaningful response. Please try again.";
        }

        // Guard 2: punctuation-only garbage (Bug-1 fix)
        long alphaCount = stripped.chars().filter(Character::isLetterOrDigit).count();
        if (stripped.length() < MIN_ANSWER_ALPHA_LENGTH && alphaCount == 0) {
            log.warnf("ANSWER-GUARD punctuation-only answer '%s' (tokens=%d) for session %s — retry prompt",
                    stripped, outputTokens, sessionId);
            return "I wasn't able to generate a meaningful response. Please try again.";
        }

        // Guard 3: compaction RAG-echo (delegates pattern ref to AgentSentinels)
        if (AgentSentinels.CONTEXT_RAG_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf("ANSWER-GUARD detected RAG echo in answer for session %s — stripping header", sessionId);
            stripped = stripContextHeader(stripped, AgentSentinels.CONTEXT_RAG_ECHO_PATTERN);
            if (stripped.isBlank()) {
                return "I wasn't able to generate a clean response. Please try again.";
            }
        }

        // Guard 4: compaction "Retrieved Context" echo
        if (AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf("ANSWER-GUARD detected Retrieved Context echo in answer for session %s — stripping header", sessionId);
            stripped = stripContextHeader(stripped, AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN);
            if (stripped.isBlank()) {
                return "I wasn't able to generate a clean response. Please try again.";
            }
        }

        // Guard 5: system-prompt echo (delegates pattern ref to AgentSentinels)
        if (AgentSentinels.SYSTEM_PROMPT_ECHO_PATTERN.matcher(stripped).find()) {
            log.warnf("ANSWER-GUARD detected system prompt echo in answer for session %s — stripping internal context", sessionId);
            int echoStart = findEchoStart(stripped, AgentSentinels.SYSTEM_PROMPT_ECHO_PATTERN);
            if (echoStart > 0) {
                String salvaged = stripped.substring(0, echoStart).strip();
                if (!salvaged.isBlank() && salvaged.length() >= MIN_ANSWER_ALPHA_LENGTH) {
                    return salvaged;
                }
            }
            return "I wasn't able to generate a clean response. Please try again.";
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
