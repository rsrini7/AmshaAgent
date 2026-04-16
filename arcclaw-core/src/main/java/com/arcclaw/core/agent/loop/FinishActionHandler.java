package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import org.apache.commons.text.StringEscapeUtils;
import org.jboss.logging.Logger;

/**
 * Processes FINISH-action steps and produces a final {@link AgentResult}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Extract the candidate answer from a FINISH step</li>
 *   <li>Detect and handle raw tool-call leaks (retry hint or strip-and-salvage)</li>
 *   <li>Apply {@link CodeGenerationPolicy} for non-coder roles</li>
 *   <li>Guard the final answer via {@link AnswerQualityGuard}</li>
 *   <li>Return an {@link AgentResult} to the caller</li>
 * </ul>
 *
 * <p><strong>§7.14 (INVARIANT — MUST NOT):</strong> This handler MUST NOT call
 * {@code agentStore.save()} for {@link ReasoningStep} history. It returns an
 * {@link AgentResult} only. {@link AgentLoop} owns all persistence triggered by
 * that result.
 *
 * <p><strong>§3.6 (MUST NOT):</strong> This handler MUST NOT own
 * {@link ChatMemoryWriter}. The caller ({@link AgentStateMachineRunner}) calls
 * {@code writer.saveAnswer()} after receiving the result back.
 *
 * <p><strong>§5.9 / §5.10 (MUST NOT):</strong> No {@code @Transactional}.
 * This class lives in the transaction-free zone.
 */
@ApplicationScoped
public class FinishActionHandler {

    private static final Logger log = Logger.getLogger(FinishActionHandler.class);

    // -------------------------------------------------------------------------
    // Salvage constants — named, not inline literals (§ analysis recommendation)
    // -------------------------------------------------------------------------

    /** Minimum character length for a salvaged answer to be accepted. */
    static final int SALVAGE_MIN_LENGTH = 5;

    /**
     * Phrase that indicates the stripped answer is a generic "task completed"
     * placeholder — not real user-facing content — and must be rejected.
     */
    static final String SALVAGE_REJECT_PHRASE = "completed the task";

    /**
     * Matches exception name in thrown form only — ExceptionType: message.
     * Prevents internal JVM exception messages from leaking, while allowing
     * analytical prose (e.g. "The NullPointerException occurs because...")
     * for debugger roles (§ analysis protection).
     */
    private static final Pattern INTERNAL_JVM_ERROR_PATTERN = Pattern.compile(
            "(?:java\\.lang\\.)?" +
            "(?:NullPointerException|IllegalArgumentException|" +
            "IllegalStateException|StackOverflowError|OutOfMemoryError)" +
            "(?::\\s.+)",
            Pattern.MULTILINE
    );

    @Inject
    AnswerQualityGuard qualityGuard;

    @Inject
    CodeGenerationPolicy codePolicy;

    @Inject
    RateLimitTracker rateLimitTracker;

    @Inject
    Instance<AgentObserver> observers;

    /**
     * Encapsulates the outcome of finish-action processing so the caller can
     * decide what to do next without inspecting answer text directly.
     */
    public sealed interface FinishOutcome
            permits FinishOutcome.Completed, FinishOutcome.RetryLeak, FinishOutcome.SalvageFailed {

        /**
         * The answer passed all guards — return it as the session result.
         *
         * @param result the final {@link AgentResult} (Finished or Failed)
         * @param answer the clean answer string; the caller writes this to
         *               ChatMemoryWriter
         */
        record Completed(AgentResult result, String answer) implements FinishOutcome {}

        /**
         * A raw tool-call leak was detected and is retryable. The caller should
         * add {@code correctedStep} to history and set {@code correctionHint} as
         * the next {@code transientError}.
         *
         * @param correctedStep    the step with the leak stripped (ready for history)
         * @param correctionHint   the self-correction message for the next LLM turn
         * @param leakedToolName   the leaked tool name (for log/metrics)
         */
        record RetryLeak(ReasoningStep correctedStep,
                         String correctionHint,
                         String leakedToolName) implements FinishOutcome {}

        /**
         * The schema self-correction budget was exhausted and no salvageable
         * answer remains.
         *
         * @param result a {@link AgentResult#failed} result ready to return
         */
        record SalvageFailed(AgentResult result) implements FinishOutcome {}
    }

    /**
     * Processes a FINISH step and returns a {@link FinishOutcome} describing
     * what should happen next.
     *
     * <p>The caller ({@link AgentStateMachineRunner}) is responsible for:
     * <ul>
     *   <li>Adding any {@link FinishOutcome.RetryLeak#correctedStep()} to
     *       history before the next iteration.</li>
     *   <li>Calling {@code writer.saveAnswer()} when the outcome is
     *       {@link FinishOutcome.Completed}.</li>
     *   <li>Never calling {@code agentStore.save()} based on this outcome
     *       (§7.14).</li>
     * </ul>
     *
     * @param sessionId         the active session ID
     * @param agentRole         the canonical agent role
     * @param history           the current in-memory reasoning history (read-only)
     * @param step              the FINISH {@link ReasoningStep} from the engine
     * @param llmSchemaErrorCount the current schema-error retry count (used to
     *                           decide salvage vs. hard-fail)
     * @return a {@link FinishOutcome} — never {@code null}
     */
    public FinishOutcome handle(String sessionId,
                                String agentRole,
                                List<ReasoningStep> history,
                                ReasoningStep step,
                                int llmSchemaErrorCount) {

        // --- Extract candidate answer ---
        String candidateAnswer = step.thought();
        if (step.actionInput() != null
                && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
            candidateAnswer = String.valueOf(
                    step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY));
        }

        // --- Raw tool-call leak detection ---
        if (qualityGuard.isRawToolCallLeak(candidateAnswer)) {
            return handleLeak(candidateAnswer, step, history, llmSchemaErrorCount, sessionId);
        }

        // --- HTML unescape + unicode normalisation ---
        String answer = candidateAnswer;
        if (answer != null) {
            answer = StringEscapeUtils.unescapeHtml4(answer);
            answer = answer
                    .replace("\\u003c", "<")
                    .replace("\\u003e", ">")
                    .replace("\\u0026", "&")
                    .replace("\\u0027", "'");
        }

        // --- Code-generation policy ---
        if (answer != null && codePolicy.isGeneratedCodeBlock(answer, agentRole)
                && !codePolicy.canGenerateCode(agentRole)) {
            String refusal = codePolicy.buildRefusalMessage(agentRole);
            log.infof("FINISH-HANDLER code-gen blocked for role '%s' in session %s", agentRole, sessionId);
            AgentResult result = AgentResult.finished(refusal, List.copyOf(history));
            return new FinishOutcome.Completed(result, refusal);
        }

        // --- Sanitization ---
        answer = AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer);
        answer = sanitizeErrorForUser(answer, sessionId);

        // --- Quality guard (null/blank, short, echo strips) ---
        int outputTokens = step.outputTokens() > 0 ? step.outputTokens() : 0;
        answer = qualityGuard.guardFinalAnswer(answer, outputTokens, sessionId);

        // --- Observers ---
        final String finalAnswer = answer;
        final ReasoningStep completedStep = step.withObservation("Task completed.");
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

        AgentResult result = AgentResult.finished(finalAnswer, List.copyOf(history));
        return new FinishOutcome.Completed(result, finalAnswer);
    }

    /**
     * Prevents internal JVM exception messages (e.g. NullPointerException) from
     * leaking into the final answer. Surfacing these to users is a security
     * and UX risk.
     */
    private String sanitizeErrorForUser(String answer, String sessionId) {
        if (answer == null) return null;

        // §7.33: Inner sessions (delegate prefix) produce developer analysis — never sanitize
        if (sessionId != null && sessionId.contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) {
            return answer;
        }

        if (INTERNAL_JVM_ERROR_PATTERN.matcher(answer).find()) {
            log.errorf("INTERNAL-LEAK BLOCKED in session %s: detected JVM exception in answer", sessionId);
            return "I encountered an internal error while formatting the response. Please try again.";
        }
        return answer;
    }

    // -------------------------------------------------------------------------
    // Leak handling (retry or salvage)
    // -------------------------------------------------------------------------

    private FinishOutcome handleLeak(String candidateAnswer,
                                     ReasoningStep step,
                                     List<ReasoningStep> history,
                                     int llmSchemaErrorCount,
                                     String sessionId) {

        boolean isRawJson = AnswerQualityGuard.RAW_TOOL_JSON_PATTERN.matcher(candidateAnswer).find();
        boolean isNvidiaLeak = AnswerQualityGuard.NVIDIA_TOOL_CALL_PATTERN.matcher(candidateAnswer).find();

        // Rate-limited: strip and return immediately rather than retrying
        if ((isRawJson || isNvidiaLeak) && rateLimitTracker.isBackingOff("primary")) {
            log.warnf("FINISH-HANDLER rate-limited — stripping leak instead of retrying for session %s", sessionId);
            String stripped = qualityGuard.stripRawToolJson(candidateAnswer);
            AgentResult result = AgentResult.finished(stripped, List.copyOf(history));
            return new FinishOutcome.Completed(result, stripped);
        }

        // Within retry budget: strip + enqueue correction
        if (llmSchemaErrorCount < 2) {
            log.warnf("STATE-MACHINE session %s FINISH answer contains raw tool call JSON — retrying (attempt %d)",
                    sessionId, llmSchemaErrorCount + 1);

            // Strip leak BEFORE adding to history so the LLM doesn't see the leak again
            String cleanThought = qualityGuard.stripRawToolJson(candidateAnswer);
            ReasoningStep cleanStep = step.withObservation(cleanThought);

            String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
            String correctionHint = "System: Your last response was a raw tool call JSON or TOOLCALLS format "
                    + "written as plain text, not a valid FINISH answer. You MUST invoke tools through the tool "
                    + "mechanism — NEVER write TOOLCALLS or raw JSON in your answer text. Retry now: call "
                    + leakedTool + " properly using the tool calling API, or FINISH with a plain text answer.";

            return new FinishOutcome.RetryLeak(cleanStep, correctionHint, leakedTool);
        }

        // Budget exhausted — attempt salvage
        return salvage(candidateAnswer, history, sessionId);
    }

    private FinishOutcome salvage(String candidateAnswer,
                                  List<ReasoningStep> history,
                                  String sessionId) {
        String salvaged = qualityGuard.stripRawToolJson(candidateAnswer);

        if (!salvaged.isBlank()
                && salvaged.length() > SALVAGE_MIN_LENGTH
                && !salvaged.contains(SALVAGE_REJECT_PHRASE)) {
            log.errorf("STATE-MACHINE session %s exceeded schema self-correction limit after raw tool call leak. "
                    + "Salvaging partially clean answer.", sessionId);
            AgentResult result = AgentResult.finished(salvaged, List.copyOf(history));
            return new FinishOutcome.Completed(result, salvaged);
        }

        log.errorf("STATE-MACHINE session %s exceeded schema self-correction limit. No salvageable answer found.",
                sessionId);
        AgentResult failed = AgentResult.failed(
                "I encountered a formatting error and could not complete your request. Please try again.",
                AgentResult.FailureCategory.SCHEMA_ERROR,
                List.copyOf(history));
        return new FinishOutcome.SalvageFailed(failed);
    }
}
