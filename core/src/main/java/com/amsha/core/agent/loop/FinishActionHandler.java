package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.classifier.api.AnswerQualityAction;
import com.amsha.classifier.api.AnswerQualityContext;
import com.amsha.classifier.api.AnswerQualityShadowContext;
import com.amsha.classifier.api.ClassifierResult;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.ReasoningStepSupport;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.message.UserMessages;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.tool.api.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private final AnswerGuardStrategy qualityGuard;
    private final Instance<AgentObserver> observers;
    private final FinishOutcomeProcessor resultProcessor;
    private final IntentClassificationService intentClassifier;
    private final FinishToolInjectionPolicy toolInjectionPolicy;

    @Inject
    AnswerQualityShadowEvaluationService answerQualityShadowEvaluation;

    @Inject
    AnswerQualityDispatcher answerQualityDispatcher;

    /**
     * Optional escalation-tier probe for memory-synthesis corrections. Null in
     * manually-constructed test harnesses; absence means "no higher tier" and the
     * deterministic verbatim-memory fallback fires one attempt earlier.
     */
    @Inject
    ModelRouter modelRouter;

    @Inject
    public FinishActionHandler(
            AnswerGuardStrategy qualityGuard,
            CodeGenerationPolicy codePolicy,
            RateLimitTracker rateLimitTracker,
            Instance<AgentObserver> observers,
            CorrectionStrategy correctionStrategy,
            ObjectMapper objectMapper,
            TextPatternUtils textPatternUtils,
            ToolRegistry toolRegistry,
            RoleRegistry roleRegistry,
            IntentClassificationService intentClassifier) {
        this.qualityGuard = qualityGuard;
        this.observers = observers;
        this.intentClassifier = intentClassifier;
        this.resultProcessor = new FinishOutcomeProcessor(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                correctionStrategy,
                textPatternUtils,
                toolRegistry,
                new HallucinationDetector(objectMapper),
                intentClassifier);
        this.toolInjectionPolicy =
                new FinishToolInjectionPolicy(intentClassifier, toolRegistry, roleRegistry, resultProcessor);
    }

    public FinishActionHandler(
            AnswerGuardStrategy qualityGuard,
            CodeGenerationPolicy codePolicy,
            RateLimitTracker rateLimitTracker,
            Instance<AgentObserver> observers,
            CorrectionStrategy correctionStrategy,
            ObjectMapper objectMapper,
            TextPatternUtils textPatternUtils,
            ToolRegistry toolRegistry,
            RoleRegistry roleRegistry) {
        this(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                observers,
                correctionStrategy,
                objectMapper,
                textPatternUtils,
                toolRegistry,
                roleRegistry,
                new IntentClassificationService());
    }

    /**
     * Encapsulates the outcome of finish-action processing so the caller can
     * decide what to do next without inspecting answer text directly.
     */
    public sealed interface FinishOutcome
            permits FinishOutcome.Completed,
                    FinishOutcome.RetryLeak,
                    FinishOutcome.Correction,
                    FinishOutcome.SynthesisCorrection,
                    FinishOutcome.SalvageFailed,
                    FinishOutcome.ExecuteLeakedTool,
                    FinishOutcome.InjectToolCall {

        /**
         * The answer passed all guards - return it as the session result.
         */
        record Completed(AgentResult result, String answer) implements FinishOutcome {}

        /**
         * A raw tool-call leak was detected and is retryable.
         */
        record RetryLeak(ReasoningStep correctedStep, String correctionHint, String leakedToolName)
                implements FinishOutcome {}

        /**
         * A hallucination or policy violation was detected that requires a corrective turn.
         */
        record Correction(ReasoningStep correctedStep, String correctionHint) implements FinishOutcome {}

        /**
         * Memory-search results exist but the answer disclaims knowledge. Carries the verbatim
         * memory result so the handler can budget the correction and fall back to quoting the
         * stored facts when the model cannot synthesize them.
         */
        record SynthesisCorrection(ReasoningStep correctedStep, String correctionHint, String memoryResult)
                implements FinishOutcome {}

        /**
         * The schema self-correction budget was exhausted and no salvageable
         * answer remains.
         */
        record SalvageFailed(AgentResult result) implements FinishOutcome {}

        /**
         * A raw tool-call leak was detected and parsed into an executable tool call.
         * The router should execute the tool and continue the loop.
         */
        record ExecuteLeakedTool(String toolName, Map<String, Object> arguments, ReasoningStep correctedStep)
                implements FinishOutcome {}

        /**
         * The agent finished without calling any tools, but the goal suggests a tool should have been used.
         * The router should inject and execute the tool call, then continue the loop.
         */
        record InjectToolCall(String toolName, Map<String, Object> arguments, ReasoningStep correctedStep)
                implements FinishOutcome {}
    }

    public FinishOutcome handle(
            AgentExecutionContext context, List<ReasoningStep> history, ReasoningStep step, int llmSchemaErrorCount) {
        if (step instanceof ReasoningStep.FinishStep finishStep) {
            return handle(context, history, finishStep, llmSchemaErrorCount);
        }
        if (step != null && ReasoningStep.FINISH.equals(step.action())) {
            // Reconstruct as a FinishStep
            ReasoningStep.FinishStep fs = new ReasoningStep.FinishStep(step.thought(), step.actionInput(), 0, 0, false);
            return handle(context, history, fs, llmSchemaErrorCount);
        }
        // §Issue-4 Fix: Graceful fallback instead of unchecked exception for non-migrated callers.
        // This path should be unreachable after full migration to ReasoningStep.FinishStep,
        // but we guard defensively to avoid crashing the agent loop.
        log.warnf(
                "FINISH-HANDLER: unexpected step type %s passed to handle() bridge — returning Correction",
                step != null ? step.getClass().getSimpleName() : "null");
        ReasoningStep correctedStep = step != null ? step.withObservation("Unexpected step type for FINISH") : null;
        context.setForceFinishRetry(true);
        return new FinishOutcome.Correction(
                correctedStep,
                "System: Expected a FINISH action but received an unexpected step type. Please FINISH with a plain text answer.");
    }

    public FinishOutcome handle(
            AgentExecutionContext context,
            List<ReasoningStep> history,
            ReasoningStep.FinishStep step,
            int llmSchemaErrorCount) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        // --- Extract and strip candidate answer ---
        String candidateAnswer = resultProcessor.extractCandidateAnswer(step);

        if (step.actionInput() != null
                && Boolean.TRUE.equals(step.actionInput().get(ReasoningStep.ACTION_INPUT_INTERCEPTED_EXECUTION_KEY))) {
            if (context.goal() != null
                    && intentClassifier != null
                    && intentClassifier.isSimpleConversational(context.goal())) {
                log.warnf(
                        "FINISH-HANDLER: intercepted unrequested execution in simple chitchat for session %s - applying chitchat protections",
                        sessionId);
                boolean isGreeting = intentClassifier.isGreeting(context.goal());
                String fallback = AgentSentinels.resolveConversationalFallback(isGreeting);
                return new FinishOutcome.Completed(AgentResult.finished(fallback, List.copyOf(history)), fallback);
            }
            AgentResult result = AgentResult.finished(candidateAnswer, List.copyOf(history));
            return new FinishOutcome.Completed(result, candidateAnswer);
        }

        // --- Raw tool-call leak detection ---
        if (qualityGuard.isRawToolCallLeak(candidateAnswer)) {
            FinishOutcome outcome = resultProcessor.handleLeak(
                    candidateAnswer, step, history, llmSchemaErrorCount, sessionId, context.goal());
            if (outcome instanceof FinishOutcome.RetryLeak) {
                context.setForceFinishRetry(true);
            }
            return outcome;
        }

        // --- HTML unescape + unicode normalisation + JSON strip ---
        String answer = resultProcessor.unescapeAndNormalize(candidateAnswer);

        // --- Arbitrary JSON response detection (not tool-call JSON) ---
        if (resultProcessor.looksLikeJson(answer)) {
            log.warnf(
                    "FINISH-HANDLER: Answer is raw JSON (not tool-call leak) for session %s - retrying with natural-language hint",
                    sessionId);
            ReasoningStep cleanStep = step.withObservation("Your response was raw JSON instead of natural language.");
            if (intentClassifier != null && intentClassifier.isSimpleConversational(context.goal())) {
                if (context.isForceFinishRetry()) {
                    boolean isGreeting = intentClassifier.isGreeting(context.goal());
                    String fallback = AgentSentinels.resolveConversationalFallback(isGreeting);
                    log.warnf(
                            "FINISH-HANDLER: forcing safe fallback after JSON leak in simple chitchat for session %s",
                            sessionId);
                    return new FinishOutcome.Completed(AgentResult.finished(fallback, List.copyOf(history)), fallback);
                }
                String hint = "System: STOP. Your response was a raw JSON object/array, NOT a natural language answer. "
                        + "This is a simple conversational message. Answer in plain prose only. "
                        + "Do NOT output JSON structures or code.";
                context.setForceFinishRetry(true);
                return new FinishOutcome.Correction(cleanStep, hint);
            }
            String hint = "System: STOP. Your response was a raw JSON object/array, NOT a natural language answer. "
                    + "You MUST respond in plain text prose. Do NOT output JSON structures, code blocks, or data formats. "
                    + "Answer the user's question directly in natural language.";
            context.setForceFinishRetry(true);
            return new FinishOutcome.Correction(cleanStep, hint);
        }

        // --- Hallucination detection ---
        FinishOutcome hallucinationCheck = resultProcessor.checkHallucination(sessionId, history, step, answer);
        if (hallucinationCheck instanceof FinishOutcome.SynthesisCorrection synthesis) {
            return handleSynthesisCorrection(context, history, synthesis);
        }
        if (hallucinationCheck != null) {
            return hallucinationCheck;
        }

        // --- Unfulfilled-work honest exit ---
        // When a tool was denied at a permission boundary and NO real tool execution
        // succeeded in this run, any narrative claiming performed work is fabrication.
        // Replace the model's answer with a deterministic, factual disclosure.
        FinishOutcome honestExit = unfulfilledPermissionDeniedWork(sessionId, agentRole, history);
        if (honestExit != null) {
            return honestExit;
        }

        // --- Code-generation policy ---
        FinishOutcome codePolicyOutcome =
                resultProcessor.applyCodePolicy(sessionId, agentRole, context.goal(), history, answer, step);
        if (codePolicyOutcome != null) {
            if (intentClassifier != null
                    && intentClassifier.isSimpleConversational(context.goal())
                    && context.isForceFinishRetry()) {
                boolean isGreeting = intentClassifier.isGreeting(context.goal());
                String fallback = AgentSentinels.resolveConversationalFallback(isGreeting);
                log.warnf(
                        "FINISH-HANDLER: forcing safe fallback after code block in simple chitchat for session %s",
                        sessionId);
                return new FinishOutcome.Completed(AgentResult.finished(fallback, List.copyOf(history)), fallback);
            }
            // Correction: LLM will retry with delegation instead of completing with refusal
            if (codePolicyOutcome instanceof FinishOutcome.Correction) {
                context.setForceFinishRetry(true);
            }
            return codePolicyOutcome;
        }

        // --- Sanitization ---
        answer = AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer);
        answer = resultProcessor.sanitizeErrorForUser(answer, sessionId);

        // --- Quality guard (null/blank, short, echo strips, leaks) ---
        AnswerGuardStrategy.AnswerGuardResult guardResult = qualityGuard.guard(answer, history);
        answer = guardResult.answer();
        if (guardResult.isFailure()) {
            log.warnf(
                    "FINISH-GUARD failure in session %s: %s (possible model collapse)",
                    sessionId, guardResult.failureReason());
            ReasoningStep correctedStep =
                    step.withObservation("Quality guard rejected the answer: " + guardResult.failureReason());
            String hint = "Your previous answer was rejected by the quality guard (" + guardResult.failureReason()
                    + "). Please provide a complete, well-formed response. Do NOT output raw JSON, tool calls, or placeholders.";
            context.setForceFinishRetry(true);
            return new FinishOutcome.Correction(correctedStep, hint);
        }

        // --- Tool injection: if agent finished without calling tools, inject the appropriate one ---
        FinishOutcome injected = toolInjectionPolicy.tryInjectToolCall(context, history, step, answer, sessionId);
        if (injected != null) {
            if (injected instanceof FinishOutcome.Correction) {
                context.setForceFinishRetry(true);
            }
            return injected;
        }

        boolean toolInvokedInTurn = history.stream().anyMatch(ReasoningStepSupport::isToolCallStep);
        FinishOutcome qualityDecision = applyAnswerQualityInterceptors(context, step, answer, toolInvokedInTurn);
        if (qualityDecision != null) return qualityDecision;

        // --- Observers ---
        final String finalAnswer = answer;
        final ReasoningStep completedStep = step.withObservation("Task completed.");
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

        observeAcceptedAnswer(context, finalAnswer, toolInvokedInTurn);
        AgentResult result = AgentResult.finished(finalAnswer, List.copyOf(history));
        return new FinishOutcome.Completed(result, finalAnswer);
    }

    private FinishOutcome applyAnswerQualityInterceptors(
            AgentExecutionContext context, ReasoningStep.FinishStep step, String answer, boolean toolInvokedInTurn) {
        if (answerQualityDispatcher == null) return null;
        try {
            ClassifierResult<AnswerQualityAction> result = answerQualityDispatcher.evaluate(
                    new AnswerQualityContext(context.agentRole(), context.goal(), answer, toolInvokedInTurn));
            if (result instanceof ClassifierResult.Opinion<AnswerQualityAction> opinion
                    && opinion.value() == AnswerQualityAction.REQUEST_RETRY
                    && !toolInvokedInTurn
                    && context.tryConsumeAnswerQualityRetry()) {
                context.setForceFinishRetry(true);
                ReasoningStep correctedStep =
                        step.withObservation("Answer-quality interceptor requested the single pre-tool retry.");
                return new FinishOutcome.Correction(
                        correctedStep,
                        "Your previous response did not meet the configured answer-quality threshold. "
                                + "Provide one improved final answer without invoking a tool.");
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Optional answer-quality decisions fail open to the authoritative accepted answer.
        }
        return null;
    }

    /**
     * Bounded economics ladder for repeated memory-synthesis failures. The memory tools
     * returned results but the model keeps disclaiming knowledge, so blind retries against
     * the same cheap slot deterministically burn the iteration budget (observed on native
     * runs: recall → archival → core read, then two identical blocked FINISHes = 5/5 steps).
     *
     * <p>Ladder: attempt 1 gets the fill-in-template correction hint; attempt 2 is escalated
     * to a non-lightweight model tier when one is configured, otherwise it exits immediately
     * with the verbatim memory content; attempt 3+ always exits with the verbatim content.
     * Termination is guaranteed within maxIterations and the user always receives the stored
     * facts rather than a false disclaimer.
     */
    private FinishOutcome handleSynthesisCorrection(
            AgentExecutionContext context, List<ReasoningStep> history, FinishOutcome.SynthesisCorrection synthesis) {
        int attempt = context.bumpMemorySynthesisCorrections();
        String sessionId = context.sessionId();
        boolean escalationTierAvailable = modelRouter != null
                && context.agentRole() != null
                && modelRouter.hasDistinctEscalationTier(context.agentRole());

        if (attempt == 1) {
            context.setForceFinishRetry(true);
            log.infof(
                    "FINISH-HANDLER: memory-synthesis correction 1/2 for session %s — template hint, same tier",
                    sessionId);
            return new FinishOutcome.Correction(synthesis.correctedStep(), synthesis.correctionHint());
        }
        if (attempt == 2 && escalationTierAvailable) {
            context.requestReasoningEscalation();
            context.setForceFinishRetry(true);
            log.infof(
                    "FINISH-HANDLER: memory-synthesis correction 2 for session %s — escalating next reasoning call to non-lightweight tier",
                    sessionId);
            return new FinishOutcome.Correction(synthesis.correctedStep(), synthesis.correctionHint());
        }

        // Budget exhausted or nothing better to escalate to: exit honestly with the stored facts.
        String fallback = "Here's what my memory of you contains:\n"
                + synthesis.memoryResult()
                + "\n\n(Quoted directly from your stored memory — I couldn't summarize it reliably just now.)";
        log.warnf(
                "FINISH-HANDLER: memory-synthesis correction budget exhausted after %d attempt(s) for session %s — exiting with verbatim memory content",
                attempt, sessionId);
        return new FinishOutcome.Completed(AgentResult.finished(fallback, List.copyOf(history)), fallback);
    }

    private void observeAcceptedAnswer(AgentExecutionContext context, String answer, boolean toolInvokedInTurn) {
        try {
            if (answerQualityShadowEvaluation != null) {
                answerQualityShadowEvaluation.observeAccepted(
                        context.sessionId(),
                        new AnswerQualityShadowContext(context.agentRole(), context.goal(), answer, toolInvokedInTurn));
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A shadow-only observer must never alter an accepted FINISH answer.
        }
    }

    /**
     * Observation fragments produced by permission-boundary denials across the guard,
     * governance, and resume paths. A finish that follows such a denial without any
     * successful real tool execution must not narrate performed work.
     */
    private static final List<String> PERMISSION_DENIAL_MARKERS =
            List.of("not permitted to use tool", "blocked by safety guard", "not recognized or permitted");

    /**
     * Returns a deterministic honest-exit outcome when the history shows a
     * permission-denied tool attempt and zero successful real tool executions;
     * {@code null} when the answer may proceed through normal processing.
     */
    private FinishOutcome unfulfilledPermissionDeniedWork(
            String sessionId, String agentRole, List<ReasoningStep> history) {
        String deniedToolName = null;
        boolean hasSuccessfulRealTool = false;
        for (ReasoningStep s : history) {
            if (!ReasoningStepSupport.isToolCallStep(s)) {
                continue;
            }
            // Escalation-synthesis markers are recorded as tool-call steps but are not
            // real executions; they must not mask the denial.
            if (s.action() != null && s.action().startsWith("escalation:")) {
                continue;
            }
            if (Boolean.TRUE.equals(s.success())) {
                hasSuccessfulRealTool = true;
            } else if (deniedToolName == null && s.observation() != null && isPermissionDenial(s.observation())) {
                deniedToolName = s.action();
            }
        }
        if (deniedToolName == null || hasSuccessfulRealTool) {
            return null;
        }
        log.warnf(
                "FINISH-HANDLER: suppressing unfulfilled-work narrative after permission denial of '%s' in session %s",
                deniedToolName, sessionId);
        String honestAnswer = UserMessages.PERMISSION_DENIED_NO_WORK_FORMAT.formatted(deniedToolName, agentRole);
        return new FinishOutcome.Completed(AgentResult.finished(honestAnswer, List.copyOf(history)), honestAnswer);
    }

    private static boolean isPermissionDenial(String observation) {
        String lower = observation.toLowerCase(Locale.ROOT);
        return PERMISSION_DENIAL_MARKERS.stream().anyMatch(lower::contains);
    }
}
