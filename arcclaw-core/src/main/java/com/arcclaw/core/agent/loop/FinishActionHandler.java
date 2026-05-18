package com.arcclaw.core.agent.loop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.TextPatternUtils;
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
    private final ToolRegistry toolRegistry;

    @Inject
    public FinishActionHandler(AnswerGuardStrategy qualityGuard,
                               CodeGenerationPolicy codePolicy,
                               RateLimitTracker rateLimitTracker,
                               Instance<AgentObserver> observers,
                               CorrectionStrategy correctionStrategy,
                               ObjectMapper objectMapper,
                               TextPatternUtils textPatternUtils,
                               ToolRegistry toolRegistry) {
        this.qualityGuard = qualityGuard;
        this.observers = observers;
        this.toolRegistry = toolRegistry;
        this.resultProcessor = new FinishOutcomeProcessor(
                qualityGuard, codePolicy, rateLimitTracker, correctionStrategy, objectMapper, textPatternUtils, toolRegistry);
    }

    /**
     * Encapsulates the outcome of finish-action processing so the caller can
     * decide what to do next without inspecting answer text directly.
     */
    public sealed interface FinishOutcome
            permits FinishOutcome.Completed, FinishOutcome.RetryLeak, FinishOutcome.Correction, FinishOutcome.SalvageFailed, FinishOutcome.ExecuteLeakedTool, FinishOutcome.InjectToolCall {

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

        /**
         * A raw tool-call leak was detected and parsed into an executable tool call.
         * The router should execute the tool and continue the loop.
         */
        record ExecuteLeakedTool(String toolName,
                                 Map<String, Object> arguments,
                                 ReasoningStep correctedStep) implements FinishOutcome {}

        /**
         * The agent finished without calling any tools, but the goal suggests a tool should have been used.
         * The router should inject and execute the tool call, then continue the loop.
         */
        record InjectToolCall(String toolName,
                              Map<String, Object> arguments,
                              ReasoningStep correctedStep) implements FinishOutcome {}
    }

    public FinishOutcome handle(AgentExecutionContext context,
                                List<ReasoningStep> history,
                                ReasoningStep step,
                                int llmSchemaErrorCount) {
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
        log.warnf("FINISH-HANDLER: unexpected step type %s passed to handle() bridge — returning Correction",
                step != null ? step.getClass().getSimpleName() : "null");
        ReasoningStep correctedStep = step != null ? step.withObservation("Unexpected step type for FINISH") : null;
        return new FinishOutcome.Correction(correctedStep,
                "System: Expected a FINISH action but received an unexpected step type. Please FINISH with a plain text answer.");
    }

    public FinishOutcome handle(AgentExecutionContext context,
                                List<ReasoningStep> history,
                                ReasoningStep.FinishStep step,
                                int llmSchemaErrorCount) {

        String sessionId = context.sessionId();
        String agentRole = context.agentRole();

        // --- Extract and strip candidate answer ---
        String candidateAnswer = resultProcessor.extractCandidateAnswer(step);

        // --- Raw tool-call leak detection ---
        if (qualityGuard.isRawToolCallLeak(candidateAnswer)) {
            FinishOutcome outcome = resultProcessor.handleLeak(candidateAnswer, step, history, llmSchemaErrorCount, sessionId, context.goal());
            if (outcome instanceof FinishOutcome.RetryLeak) {
                context.setForceFinishRetry(true);
            }
            return outcome;
        }

        // --- HTML unescape + unicode normalisation + JSON strip ---
        String answer = resultProcessor.unescapeAndNormalize(candidateAnswer);

        // --- Hallucination detection ---
        FinishOutcome hallucinationCheck = resultProcessor.checkHallucination(sessionId, history, step, answer);
        if (hallucinationCheck != null) {
            return hallucinationCheck;
        }

        // --- Code-generation policy ---
        FinishOutcome codePolicyOutcome = resultProcessor.applyCodePolicy(sessionId, agentRole, history, answer, step);
        if (codePolicyOutcome != null) {
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
            log.warnf("FINISH-GUARD failure in session %s: %s (possible model collapse)", sessionId, guardResult.failureReason());
            ReasoningStep correctedStep = step.withObservation("Quality guard rejected the answer: " + guardResult.failureReason());
            String hint = "Your previous answer was rejected by the quality guard (" + guardResult.failureReason()
                    + "). Please provide a complete, well-formed response. Do NOT output raw JSON, tool calls, or placeholders.";
            return new FinishOutcome.Correction(correctedStep, hint);
        }

        // --- Tool injection: if agent finished without calling tools, inject the appropriate one ---
        FinishOutcome injected = tryInjectToolCall(context, history, step, answer, sessionId);
        if (injected != null) {
            return injected;
        }

        // --- Observers ---
        final String finalAnswer = answer;
        final ReasoningStep completedStep = step.withObservation("Task completed.");
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

        AgentResult result = AgentResult.finished(finalAnswer, List.copyOf(history));
        return new FinishOutcome.Completed(result, finalAnswer);
    }

    /**
     * If the agent finished without using the right tool, inject it.
     * Handles two cases:
     * 1. Refusal + research goal → inject search tool
     * 2. Execution goal + no code in answer + sandbox available → inject sandbox_exec
     */
    private FinishOutcome tryInjectToolCall(AgentExecutionContext context,
                                            List<ReasoningStep> history,
                                            ReasoningStep.FinishStep step,
                                            String answer,
                                            String sessionId) {
        String goal = context.goal();
        if (goal == null || goal.isBlank()) return null;

        String availableTools = String.join(",", toolRegistry.listTools());
        boolean isRefusal = AgentSentinels.REFUSAL_PATTERN.matcher(answer).find();

        // Case 1: Refusal + research goal → inject search
        if (isRefusal && AgentSentinels.RESEARCH_KEYWORDS.matcher(goal).find()) {
            String searchTool = findSearchTool(availableTools);
            if (searchTool != null) {
                log.infof("TOOL-INJECT session %s: agent finished without searching, injecting %s", sessionId, searchTool);
                Map<String, Object> args = new HashMap<>();
                args.put("query", goal.replaceAll("(?i)^(do )?research (on )?(use )?", "").trim());
                args.put("count", 5);
                return new FinishOutcome.InjectToolCall(searchTool, args,
                        step.withObservation("Injecting search tool call that was missed"));
            }
        }

        // Case 2: Execution goal + no code in answer + sandbox available → retry with correction
        if (AgentSentinels.EXECUTION_KEYWORDS.matcher(goal).find()
                && availableTools.contains(ToolCapability.SANDBOX_EXEC.toolName())
                && !answer.contains("```")) {
            log.infof("TOOL-INJECT session %s: execution goal but no code in answer, forcing correction", sessionId);
            return new FinishOutcome.Correction(
                    step.withObservation("Agent finished without writing code for an execution goal"),
                    "STOP. You MUST write a complete Java program and call sandbox_exec to execute it. " +
                    "The sandbox_exec tool requires 'language' (e.g. 'java') and 'code' parameters. " +
                    "Do NOT explain. Do NOT research. Write the code NOW and call sandbox_exec immediately.");
        }

        return null;
    }

    private static final List<String> SEARCH_TOOL_NAMES = List.of(
            ToolCapability.WEB_SEARCH_SERPER.toolName(),
            ToolCapability.WEB_SEARCH_BRAVE.toolName(),
            "web_search", "search");

    private String findSearchTool(String availableTools) {
        for (String name : SEARCH_TOOL_NAMES) {
            if (availableTools.contains(name)) return name;
        }
        return null;
    }

}
