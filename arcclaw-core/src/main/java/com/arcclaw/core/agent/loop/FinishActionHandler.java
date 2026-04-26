package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.TextPatternUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p><strong>§7.14 (INVARIANT - MUST NOT):</strong> This handler MUST NOT call
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
    // Salvage constants - named, not inline literals (§ analysis recommendation)
    // -------------------------------------------------------------------------

    /** Minimum character length for a salvaged answer to be accepted. */
    static final int SALVAGE_MIN_LENGTH = 5;

    /**
     * Phrase that indicates the stripped answer is a generic "task completed"
     * placeholder - not real user-facing content - and must be rejected.
     */
    static final String SALVAGE_REJECT_PHRASE = "completed the task";

    /**
     * Matches exception name in thrown form only - ExceptionType: message.
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

    /**
     * Matches common token leaks at the START of an LLM response when it's
     * transitioning to/from a tool-calling state.
     * Handles Markdown formatting like **FINISH** or __Answer:__.
     */
    private static final Pattern PREFIX_TOKEN_LEAK = Pattern.compile(
            "(?i)^(\\s*[*_]*(FINISH:?|Action:\\s*FINISH|Answer:|Final Answer:|Response:)[*_]*\\s*)+",
            Pattern.MULTILINE
    );

    /**
     * Matches common token leaks at the END of an LLM response.
     * Handles Markdown formatting like **FINISH**.
     */
    private static final Pattern SUFFIX_TOKEN_LEAK = Pattern.compile(
            "(?i)\\s*[*_]*(FINISH:?|Action:\\s*FINISH)[*_]*\\s*$",
            Pattern.MULTILINE
    );

    private final AnswerGuardStrategy qualityGuard;
    private final CodeGenerationPolicy codePolicy;
    private final RateLimitTracker rateLimitTracker;
    private final Instance<AgentObserver> observers;
    private final CorrectionStrategy correctionStrategy;
    private final ObjectMapper objectMapper;

    @Inject
    public FinishActionHandler(AnswerGuardStrategy qualityGuard,
                               CodeGenerationPolicy codePolicy,
                               RateLimitTracker rateLimitTracker,
                               Instance<AgentObserver> observers,
                               CorrectionStrategy correctionStrategy,
                               ObjectMapper objectMapper) {
        this.qualityGuard = qualityGuard;
        this.codePolicy = codePolicy;
        this.rateLimitTracker = rateLimitTracker;
        this.observers = observers;
        this.correctionStrategy = correctionStrategy;
        this.objectMapper = objectMapper;
    }

    /**
     * Encapsulates the outcome of finish-action processing so the caller can
     * decide what to do next without inspecting answer text directly.
     */
    public sealed interface FinishOutcome
            permits FinishOutcome.Completed, FinishOutcome.RetryLeak, FinishOutcome.Correction, FinishOutcome.SalvageFailed {

        /**
         * The answer passed all guards - return it as the session result.
         */
        record Completed(AgentResult result, String answer) implements FinishOutcome {}

        /**
         * A raw tool-call leak was detected and is retryable.
         */
        record RetryLeak(ReasoningStep correctedStep,
                         String correctionHint,
                         String leakedToolName) implements FinishOutcome {}

        /**
         * A hallucination or policy violation was detected that requires a corrective turn.
         */
        record Correction(ReasoningStep correctedStep, String correctionHint) implements FinishOutcome {}

        /**
         * The schema self-correction budget was exhausted and no salvageable
         * answer remains.
         */
        record SalvageFailed(AgentResult result) implements FinishOutcome {}
    }

    public FinishOutcome handle(AgentExecutionContext context,
                                List<ReasoningStep> history,
                                ReasoningStep step,
                                int llmSchemaErrorCount) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        if (!ReasoningStep.FINISH.equals(step.action())) {
            ReasoningStep cleanStep = step.withObservation("System: Action must be FINISH");
            return new FinishOutcome.Correction(cleanStep, "System: You must invoke the FINISH action.");
        }

        // --- Extract and strip candidate answer ---
        String candidateAnswer = extractCandidateAnswer(step);

        // --- Raw tool-call leak detection ---
        if (qualityGuard.isRawToolCallLeak(candidateAnswer)) {
            FinishOutcome outcome = handleLeak(candidateAnswer, step, history, llmSchemaErrorCount, sessionId);
            if (outcome instanceof FinishOutcome.RetryLeak) {
                context.setForceFinishRetry(true);
            }
            return outcome;
        }

        // --- HTML unescape + unicode normalisation + JSON strip ---
        String answer = unescapeAndNormalize(candidateAnswer);

        // --- Hallucination detection ---
        FinishOutcome hallucinationCheck = checkHallucination(sessionId, history, step, answer);
        if (hallucinationCheck != null) {
            return hallucinationCheck;
        }

        // --- Code-generation policy ---
        FinishOutcome codePolicyOutcome = applyCodePolicy(sessionId, agentRole, history, answer);
        if (codePolicyOutcome != null) {
            return codePolicyOutcome;
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

    private String extractCandidateAnswer(ReasoningStep step) {
        String candidateAnswer = step.thought();
        if (step.actionInput() != null
                && step.actionInput().containsKey(ReasoningStep.ACTION_INPUT_ANSWER_KEY)) {
            candidateAnswer = String.valueOf(
                    step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY));
        }

        // 🟢 FIX: Strip "FINISH" token leaks from LLM output boundaries
        if (candidateAnswer != null) {
            candidateAnswer = candidateAnswer.strip();
            // Match one or more common standalone prefixes
            candidateAnswer = PREFIX_TOKEN_LEAK.matcher(candidateAnswer).replaceFirst("");
            // Match common standalone words/lines at the very end
            candidateAnswer = SUFFIX_TOKEN_LEAK.matcher(candidateAnswer).replaceFirst("");
            candidateAnswer = candidateAnswer.strip();
        }
        return candidateAnswer;
    }

    private String unescapeAndNormalize(String candidateAnswer) {
        if (candidateAnswer == null) return null;
        String answer = StringEscapeUtils.unescapeHtml4(candidateAnswer);
        answer = answer
                .replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0026", "&")
                .replace("\\u0027", "'");
        
        // --- Strip leaked tool-call JSON ---
        return sanitizeAnswer(answer);
    }

    private FinishOutcome checkHallucination(String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        if (answer != null && answer.contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE)) {
            boolean actuallyScheduled = historyContainsSuccessfulSchedule(history);
            
            if (!actuallyScheduled) {
                log.warnf("FINISH-HANDLER: Hallucinated scheduling success detected for session %s. " +
                          "No 'schedule_task' call found in history.", sessionId);
                
                ReasoningStep cleanStep = step.withObservation("Detected hallucinated scheduling success in FINISH answer");
                String hint = "System: STOP. You claimed '" + AgentSentinels.SCHEDULE_SUCCESS_MESSAGE + "' but you did NOT call " +
                    "the schedule_task tool in this turn. You must invoke the schedule_task tool with " +
                    "the correct cron expression and goal BEFORE calling FINISH.";
                
                return new FinishOutcome.Correction(cleanStep, hint);
            }
        }
        return null;
    }

    private boolean historyContainsSuccessfulSchedule(List<ReasoningStep> history) {
        return history.stream().anyMatch(this::stepContainsSuccessfulSchedule);
    }

    private boolean stepContainsSuccessfulSchedule(ReasoningStep step) {
        if ("schedule_task".equals(step.action()) && Boolean.TRUE.equals(step.success())) {
            return true;
        }
        if (!step.isParallelBatch() || step.actionInput() == null) {
            return false;
        }
        Object batchRaw = step.actionInput().get("batch");
        if (!(batchRaw instanceof String batchJson) || batchJson.isBlank()) {
            return false;
        }
        try {
            List<ParallelToolCall> calls = objectMapper.readValue(batchJson, new TypeReference<List<ParallelToolCall>>() {});
            return calls.stream().anyMatch(call ->
                    "schedule_task".equals(call.toolName()) && call.success());
        } catch (Exception e) {
            log.debugf("FINISH-HANDLER: failed to parse parallel batch while checking schedule success: %s", e.getMessage());
            return false;
        }
    }

    private FinishOutcome applyCodePolicy(String sessionId, String agentRole, List<ReasoningStep> history, String answer) {
        if (answer != null && codePolicy.isGeneratedCodeBlock(answer, agentRole)
                && !codePolicy.canGenerateCode(agentRole)) {
            String refusal = codePolicy.buildRefusalMessage(agentRole);
            log.infof("FINISH-HANDLER code-gen blocked for role '%s' in session %s", agentRole, sessionId);     
            AgentResult result = AgentResult.finished(refusal, List.copyOf(history));
            return new FinishOutcome.Completed(result, refusal);
        }
        return null;
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
            log.warnf("FINISH-HANDLER rate-limited - stripping leak instead of retrying for session %s", sessionId);
            String stripped = qualityGuard.stripRawToolJson(candidateAnswer);
            AgentResult result = AgentResult.finished(stripped, List.copyOf(history));
            return new FinishOutcome.Completed(result, stripped);
        }

        String cleanThought = qualityGuard.stripRawToolJson(candidateAnswer);
        if (llmSchemaErrorCount >= 1
                && !cleanThought.isBlank()
                && cleanThought.length() > SALVAGE_MIN_LENGTH
                && !cleanThought.contains(SALVAGE_REJECT_PHRASE)) {
            
            if (looksLikeJson(cleanThought)) {
                // The entire answer was a tool call JSON. Instead of failing immediately,
                // give one more retry with a strong "answer from context" hint.
                if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                    log.warnf("FINISH-HANDLER salvaged thought is still JSON for session %s - retrying with direct-answer hint", sessionId);
                    ReasoningStep cleanStep = step.withObservation("");
                    String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
                    String directHint = "System: Your response was raw JSON, NOT a valid answer. " +
                            "You are NOT allowed to write tool calls as text. " +
                            "The information the user asked for IS in the conversation history above. " +
                            "Read the history carefully and answer DIRECTLY in plain text using FINISH. " +
                            "Do NOT call any tools. Do NOT output JSON.";
                    return new FinishOutcome.RetryLeak(cleanStep, directHint, leakedTool);
                }
                
                log.warnf("FINISH-HANDLER salvaged thought is still JSON - not usable, failing turn for session %s", sessionId);
                AgentResult failed = AgentResult.failed(
                        "I encountered a formatting error (JSON echo) and could not complete your request.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history));
                return new FinishOutcome.SalvageFailed(failed);
            }

            log.warnf("FINISH-HANDLER early salvage after %d leaks for session %s - clean thought is usable",   
                    llmSchemaErrorCount, sessionId);
            return new FinishOutcome.Completed(
                    AgentResult.finished(cleanThought, List.copyOf(history)), cleanThought);
        }

        if (correctionStrategy.withinBudget(llmSchemaErrorCount)) {
            log.warnf("STATE-MACHINE session %s FINISH answer contains raw tool call JSON - retrying (attempt %d)",
                    sessionId, llmSchemaErrorCount + 1);

            ReasoningStep cleanStep = step.withObservation(cleanThought);

            String leakedTool = qualityGuard.extractLeakedToolName(candidateAnswer);
            String correctionHint = "System: Your last response was a raw tool call JSON or TOOLCALLS format "  
                    + "written as plain text, not a valid FINISH answer. You MUST invoke tools through the tool "
                    + "mechanism - NEVER write TOOLCALLS or raw JSON in your answer text. Retry now: call "     
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

            if (looksLikeJson(salvaged)) {
                log.errorf("STATE-MACHINE session %s exceeded schema limit. Salvaged thought is still JSON - failing turn.", sessionId);
                AgentResult failed = AgentResult.failed(
                        "I encountered a formatting error (JSON echo) and could not complete your request.",
                        AgentResult.FailureCategory.SCHEMA_ERROR,
                        List.copyOf(history));
                return new FinishOutcome.SalvageFailed(failed);
            }

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

    private boolean looksLikeJson(String text) {
        return TextPatternUtils.looksLikeJson(text);
    }
}
