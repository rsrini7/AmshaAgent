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
 *
 * <p>Refactored to constructor injection as part of T4-1.
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

    /**
     * Matches raw JSON tool call blocks that occasionally leak into prose answers.
     * Looks for braces containing "tool", "action", "function", or "callId".
     */
    private static final Pattern TOOL_CALL_JSON_LEAK =
            Pattern.compile("(?s)\\{\\s*\"(tool|action|function|callId)\"\\s*:.*?\\}", Pattern.DOTALL);

    private final AnswerGuardStrategy qualityGuard;
    private final CodeGenerationPolicy codePolicy;
    private final RateLimitTracker rateLimitTracker;
    private final Instance<AgentObserver> observers;
    private final CorrectionStrategy correctionStrategy;

    @Inject
    public FinishActionHandler(AnswerGuardStrategy qualityGuard,
                               CodeGenerationPolicy codePolicy,
                               RateLimitTracker rateLimitTracker,
                               Instance<AgentObserver> observers,
                               CorrectionStrategy correctionStrategy) {
        this.qualityGuard = qualityGuard;
        this.codePolicy = codePolicy;
        this.rateLimitTracker = rateLimitTracker;
        this.observers = observers;
        this.correctionStrategy = correctionStrategy;
    }

    /**
     * Encapsulates the outcome of finish-action processing so the caller can
     * decide what to do next without inspecting answer text directly.
     */
    public sealed interface FinishOutcome
            permits FinishOutcome.Completed, FinishOutcome.RetryLeak, FinishOutcome.SalvageFailed {

        /**
         * The answer passed all guards — return it as the session result.
         */
        record Completed(AgentResult result, String answer) implements FinishOutcome {}

        /**
         * A raw tool-call leak was detected and is retryable.
         */
        record RetryLeak(ReasoningStep correctedStep,
                         String correctionHint,
                         String leakedToolName) implements FinishOutcome {}

        /**
         * The schema self-correction budget was exhausted and no salvageable
         * answer remains.
         */
        record SalvageFailed(AgentResult result) implements FinishOutcome {}
    }

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
            
            // --- Strip leaked tool-call JSON ---
            answer = sanitizeAnswer(answer);
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

        // --- Quality guard (null/blank, short, echo strips, leaks) ---
        AnswerGuardStrategy.AnswerGuardResult guardResult = qualityGuard.guard(answer, history);
        answer = guardResult.answer();
        if (guardResult.isFailure()) {
            log.warnf("FINISH-GUARD failure in session %s: %s (possible model collapse)", sessionId, guardResult.failureReason());        
        }

        // --- Observers ---
        final String finalAnswer = answer;
        final ReasoningStep completedStep = step.withObservation("Task completed.");
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

        AgentResult result = AgentResult.finished(finalAnswer, List.copyOf(history));
        return new FinishOutcome.Completed(result, finalAnswer);
    }

    /**
     * Strips any leaked tool-call JSON blocks from the final answer text.
     */
    private String sanitizeAnswer(String raw) {
        if (raw == null) return null;
        String stripped = TOOL_CALL_JSON_LEAK.matcher(raw).replaceAll("").strip();
        if (!stripped.equals(raw)) {
            log.warnf("FINISH-HANDLER stripped leaked tool-call JSON from answer (%d chars removed)",
                    raw.length() - stripped.length());
        }
        return stripped;
    }

    private String sanitizeErrorForUser(String answer, String sessionId) {
        if (answer == null) return null;

        if (sessionId != null && sessionId.contains(AgentSentinels.DELEGATE_SESSION_SENTINEL)) {
            return answer;
        }

        if (INTERNAL_JVM_ERROR_PATTERN.matcher(answer).find()) {
            log.errorf("INTERNAL-LEAK BLOCKED in session %s: detected JVM exception in answer", sessionId);     
            return "I encountered an internal error while formatting the response. Please try again.";
        }
        return answer;
    }

    private FinishOutcome handleLeak(String candidateAnswer,
                                     ReasoningStep step,
                                     List<ReasoningStep> history,
                                     int llmSchemaErrorCount,
                                     String sessionId) {

        boolean isLeak = qualityGuard.isRawToolCallLeak(candidateAnswer);

        if (isLeak && rateLimitTracker.isBackingOff("primary")) {
            log.warnf("FINISH-HANDLER rate-limited — stripping leak instead of retrying for session %s", sessionId);
            String stripped = qualityGuard.stripRawToolJson(candidateAnswer);
            AgentResult result = AgentResult.finished(stripped, List.copyOf(history));
            return new FinishOutcome.Completed(result, stripped);
        }

        String cleanThought = qualityGuard.stripRawToolJson(candidateAnswer);
        if (llmSchemaErrorCount >= 1
                && !cleanThought.isBlank()
                && cleanThought.length() > SALVAGE_MIN_LENGTH
                && !cleanThought.contains(SALVAGE_REJECT_PHRASE)) {      
            log.warnf("FINISH-HANDLER early salvage after %d leaks for session %s — clean thought is usable",   
                    llmSchemaErrorCount, sessionId);
            return new FinishOutcome.Completed(
                    AgentResult.finished(cleanThought, List.copyOf(history)), cleanThought);
        }

        if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
            log.warnf("STATE-MACHINE session %s FINISH answer contains raw tool call JSON — retrying (attempt %d)",
                    sessionId, llmSchemaErrorCount + 1);

            ReasoningStep cleanStep = step.withObservation(cleanThought);

            String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
            String correctionHint = "System: Your last response was a raw tool call JSON or TOOLCALLS format "  
                    + "written as plain text, not a valid FINISH answer. You MUST invoke tools through the tool "
                    + "mechanism — NEVER write TOOLCALLS or raw JSON in your answer text. Retry now: call "     
                    + leakedTool + " properly using the tool calling API, or FINISH with a plain text answer."; 

            return new FinishOutcome.RetryLeak(cleanStep, correctionHint, leakedTool);
        }

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
