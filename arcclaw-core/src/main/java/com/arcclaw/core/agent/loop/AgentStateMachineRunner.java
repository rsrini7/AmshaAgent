package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.persona.AgentPersonaRegistry;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.security.InputSanitizer;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.util.AgentErrorMessageUtil;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Orchestrates the Thought-Plan-Act-Observe loop.
 *
 * <p>This class is now a <strong>thin coordination shell</strong>. All domain
 * logic previously embedded here has been extracted to focused collaborators:
 *
 * <ul>
 *   <li>{@link AnswerGuardStrategy} — leak detection, echo stripping, answer
 *       validation</li>
 *   <li>{@link FinishActionHandler} — FINISH step processing, salvage,
 *       code-gen policy</li>
 *   <li>{@link CorrectionStrategy} — schema self-correction budget</li>
 *   <li>{@link CodeGenerationPolicy} — code-block capability enforcement</li>
 *   <li>{@link EngineStepHandler} — LLM step execution</li>
 *   <li>{@link ToolStepHandler} — tool validation and invocation</li>
 * </ul>
 *
 * <h2>Loop-variable ownership</h2>
 * <p>{@code transientError} and {@code llmSchemaErrorCount} remain loop-scoped
 * here because:
 * <ul>
 *   <li>{@code transientError} is written by the write-intent injection on
 *       iteration 1 AND by the schema/leak correction paths.</li>
 *   <li>{@code llmSchemaErrorCount} must not be an AtomicInteger (§3.7
 *       CAS-ordering risk); it is a plain {@code int} incremented
 *       synchronously in the single-threaded loop.</li>
 * </ul>
 *
 * <h2>Persistence</h2>
 * <p>This class MUST NOT call {@code agentStore.save()} for
 * {@link ReasoningStep} history. {@link AgentLoop} owns all persistence
 * (§7.14).
 *
 * <h2>ChatMemoryWriter ownership</h2>
 * <p>{@code writer.saveAnswer()} is called HERE after receiving a
 * {@link FinishActionHandler.FinishOutcome.Completed} result — NOT inside
 * {@link FinishActionHandler} (§3.6).
 *
 * <p>Refactored to constructor injection as part of T4-1.
 */
@ApplicationScoped
public class AgentStateMachineRunner {

    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    private static final int LOG_GOAL_MAX = 80;
    private static final String GOAL_LOG_FMT = "StateMachine starting session=%s goal='%s' maxIterations=%d";

    private final EngineStepHandler engineStepHandler;
    private final ToolStepHandler toolStepHandler;
    private final ChatMemoryWriter writer;
    private final Instance<AgentObserver> observers;
    private final AgentPersonaRegistry personaRegistry;
    private final SkillRegistry skillRegistry;
    private final InputSanitizer piiScanReflex;
    private final PiiRedactionTransformer piiTransformer;
    private final AgentOtelContext agentOtelContext;
    private final CliResultBridge cliResultBridge;
    private final IntentClassificationService intentDetector;
    private final LoopExitPublisher loopExitPublisher;
    private final FinishActionHandler finishActionHandler;
    private final CorrectionStrategy correctionStrategy;

    @Inject
    public AgentStateMachineRunner(EngineStepHandler engineStepHandler,
                                   ToolStepHandler toolStepHandler,
                                   ChatMemoryWriter writer,
                                   Instance<AgentObserver> observers,
                                   AgentPersonaRegistry personaRegistry,
                                   SkillRegistry skillRegistry,
                                   @InputSanitizer.PII InputSanitizer piiScanReflex,
                                   PiiRedactionTransformer piiTransformer,
                                   AgentOtelContext agentOtelContext,
                                   CliResultBridge cliResultBridge,
                                   IntentClassificationService intentDetector,
                                   LoopExitPublisher loopExitPublisher,
                                   FinishActionHandler finishActionHandler,
                                   CorrectionStrategy correctionStrategy) {
        this.engineStepHandler = engineStepHandler;
        this.toolStepHandler = toolStepHandler;
        this.writer = writer;
        this.observers = observers;
        this.personaRegistry = personaRegistry;
        this.skillRegistry = skillRegistry;
        this.piiScanReflex = piiScanReflex;
        this.piiTransformer = piiTransformer;
        this.agentOtelContext = agentOtelContext;
        this.cliResultBridge = cliResultBridge;
        this.intentDetector = intentDetector;
        this.loopExitPublisher = loopExitPublisher;
        this.finishActionHandler = finishActionHandler;
        this.correctionStrategy = correctionStrategy;
    }

    /**
     * Executes the Thought-Plan-Act-Observe loop for the given execution context.
     *
     * <p><strong>§3.5 / §8.6:</strong> {@link BudgetExceededException} and
     * {@link BudgetWarningException} are never caught here — they propagate to
     * {@link AgentLoop}.
     *
     * @param context         the immutable execution context
     * @param engine          the reasoning engine (LLM abstraction)
     * @param resumedHistory  previously loaded history steps (may be empty)
     * @return the final {@link AgentResult} — never {@code null}
     */
    public AgentResult run(AgentExecutionContext context,
                           ReasoningEngine engine,
                           List<ReasoningStep> resumedHistory) {

        List<ReasoningStep> history = new ArrayList<>(resumedHistory);

        // Loop-scoped variables — must NOT be delegated to collaborators
        int iteration = 0;
        int llmSchemaErrorCount = 0;   // plain int — NEVER AtomicInteger (§3.7)
        int consecutiveFailures = 0;
        String transientError = null;  // written by write-intent injection AND correction paths

        String originalGoal = context.goal() != null ? context.goal() : "unknown";
        
        // --- 1. PII Redaction & Goal Preparation ---
        String goal = piiTransformer != null ? piiTransformer.transform(null, originalGoal) : originalGoal;
        String goalHash = AgentErrorMessageUtil.hashGoal(goal);

        if (log.isInfoEnabled()) {
            String sanitized = piiScanReflex != null ? piiScanReflex.sanitize(goal) : goal;
            if (sanitized == null) sanitized = goal;
            String preview = sanitized.length() > LOG_GOAL_MAX
                    ? sanitized.substring(0, LOG_GOAL_MAX) : sanitized;
            log.infof(GOAL_LOG_FMT, context.sessionId(), preview, context.maxIterations());
        }

        // Fire initial status event (§7.8)
        loopExitPublisher.publishStart(context);

        writer.saveGoal(context.sessionId(), goal);

        AgentResult finalResult = null;

        try {
            // =====================================================================
            // Main iteration loop
            // =====================================================================
            while (iteration < context.maxIterations()) {
                iteration++;
                Span iterSpan = agentOtelContext.startIterationSpan(
                        context.sessionId(), iteration, context.agentRole());
                try (Scope scope = iterSpan.makeCurrent()) {

                    log.debugf("StateMachine iteration %d/%d", iteration, context.maxIterations());

                    // --- Write-intent hint injection (iteration 1 only) ---
                    if (iteration == 1 && intentDetector.isWriteIntent(goal)) {
                        transientError = "The user wants to store data. Call ingest_document immediately. "
                                + "Pass REDACTED-* placeholders as-is. No explanation needed.";
                    }

                    // --- Engine step ---
                    StepDecision engineDecision;
                    try {
                        engineDecision = engineStepHandler.processEngineStep(
                                context, engine, history, transientError, llmSchemaErrorCount, iteration);
                    } catch (StructuredOutputException soe) {
                        // Delegate schema-error handling to CorrectionStrategy
                        if (!correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                            log.warnf("StateMachine schema self-correction exhausted for session %s", context.sessionId());
                            finalResult = AgentResult.loopExceeded(
                                    context.maxIterations(), List.copyOf(history));
                            return finalResult;
                        }

                        llmSchemaErrorCount++;
                        transientError = correctionStrategy.buildCorrectionHint(llmSchemaErrorCount, soe.getMessage());
                        log.warnf("StateMachine schema self-correction attempt %d for session %s",
                                llmSchemaErrorCount, context.sessionId());
                        continue;
                    }

                    // --- Terminal decision ---
                    if (engineDecision.type() == DecisionType.TERMINAL) {
                        finalResult = engineDecision.result();
                        return finalResult;
                    }

                    // --- Continue (schema retry without a step) ---
                    if (engineDecision.type() == DecisionType.CONTINUE) {
                        transientError = engineDecision.transientError();
                        llmSchemaErrorCount = engineDecision.llmSchemaErrorCount();
                        continue;
                    }

                    // --- Proceed with a real step ---
                    ReasoningStep step = engineDecision.step();
                    transientError = null;  // clear after a successful engine step

                    // Validation Loop (to support Deduplication retries without using a turn)
                    while (true) {
                        // --- FINISH action ---
                        if (ReasoningStep.FINISH.equals(step.action())) {
                            if (step.isParallelBatch()) {
                                log.errorf("PARALLEL INVARIANT VIOLATION: session %s parallel batch step reached "      
                                        + "FINISH handler.", context.sessionId());
                            }
                            FinishDecision finishDecision = processFinish(context, history, step, llmSchemaErrorCount);
                            if (finishDecision.result() != null) {
                                finalResult = finishDecision.result();
                                return finalResult;
                            }
                            // RetryLeak outcome: correction hint provided, continue loop
                            transientError = finishDecision.transientError();
                            llmSchemaErrorCount++;
                            break; // exit validation loop to retry engine
                        }

                        // --- Tool validation ---
                        StepDecision validationDecision = toolStepHandler.validateTool(
                                context, history, step, consecutiveFailures);

                        if (validationDecision.type() == DecisionType.TERMINAL) {
                            finalResult = validationDecision.result();
                            return finalResult;
                        }
                        
                        if (validationDecision.isRetryValidation()) {
                            consecutiveFailures = validationDecision.consecutiveFailures();
                            // Deduplication logic: validationDecision added the dedup step to history
                            break; // exit validation loop to retry engine
                        }

                        if (validationDecision.type() == DecisionType.CONTINUE) {
                             consecutiveFailures = validationDecision.consecutiveFailures();
                             // Hallucinated tool or ReAct keyword
                             break;
                        }

                        step = validationDecision.step();

                        // Re-dispatch if validateTool rewrote a hallucinated tool to FINISH
                        if (ReasoningStep.FINISH.equals(step.action())) {
                            FinishDecision finishDecision = processFinish(context, history, step, llmSchemaErrorCount);
                            if (finishDecision.result() != null) {
                                finalResult = finishDecision.result();
                                return finalResult;
                            }
                            transientError = finishDecision.transientError();
                            llmSchemaErrorCount++;
                            break; 
                        }

                        // Validation passed, add thought/action to history
                        history.add(step);

                        // --- Tool invocation ---
                        // HITL State Check
                        if (context.isStatusDirty()) {
                            var cpOpt = engineStepHandler.loadCheckpointStatus(context);
                            context.setLastCheckpointStatus(cpOpt.orElse(null));
                            if (context.lastCheckpointStatus() == CheckpointStatus.AWAITING_APPROVAL) {                                log.warnf("Session %s is still AWAITING_APPROVAL. Refusing to invoke tool "
                                        + "until state is flushed as IN_PROGRESS.", context.sessionId());
                                finalResult = AgentResult.failed("State corrupted — tool execution attempted before approval persistence completed.",
                                        AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                                return finalResult;
                            }
                        }

                        StepDecision invocationDecision = toolStepHandler.invokeToolAndObserve(
                                context, history, step, consecutiveFailures, skillRegistry.currentSnapshot());

                        if (invocationDecision.type() == DecisionType.TERMINAL) {
                            finalResult = invocationDecision.result();
                            return finalResult;
                        }

                        if (invocationDecision.result() instanceof AgentResult.AwaitingApproval) {
                            context.markStatusDirty();
                            finalResult = invocationDecision.result();
                            return finalResult;
                        }

                        consecutiveFailures = invocationDecision.consecutiveFailures();
                        break; // Move to next iteration of iteration loop
                    }

                    if (transientError != null) continue;

                } catch (Exception e) {
                    iterSpan.recordException(e);
                    iterSpan.setStatus(StatusCode.ERROR, AgentErrorMessageUtil.safeMessage(e));
                    throw e;
                } finally {
                    iterSpan.end();
                }
            }

            // Loop exhausted
            log.warnf("StateMachine exceeded %d iterations for session %s", context.maxIterations(), context.sessionId());
            finalResult = AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
            return finalResult;

        } catch (BudgetExceededException e) {
            finalResult = AgentResult.failed(e.getMessage(), AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(history));
            return finalResult;
        } catch (Exception e) {
            log.errorf("StateMachine terminal failure for session %s: %s", context.sessionId(), e.getMessage());
            finalResult = AgentResult.failed(e.getMessage(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
            throw e;
        } finally {
            AgentResult res = finalResult;
            
            // Apply persona-specific answer formatting if needed
            if (res instanceof AgentResult.Finished _ && personaRegistry != null) {
                 // Removed invalid find() call. Persona prompt extraction is handled in SystemPromptBuilder.
                 log.debugf("Loop complete for role %s", context.agentRole());
            }

            // Bridge to CLI if configured
            if (cliResultBridge != null && res != null) {
                cliResultBridge.complete(context.sessionId(), res);
            }

            loopExitPublisher.publish(context, res);
            observers.forEach(o -> {
                o.onLoopComplete(context.sessionId(), context.userId(), context.agentRole(), goalHash, res);
            });
        }
    }

    private record FinishDecision(AgentResult result, String transientError) {}

    /**
     * Processes a FINISH step via {@link FinishActionHandler}.
     *
     * <p>Returns a {@link FinishDecision} describing the calculated result or
     * the retry hint for a detected leak.
     *
     * <p>§3.6: {@code writer.saveAnswer()} is called HERE — not inside
     * {@link FinishActionHandler}.
     */
    private FinishDecision processFinish(AgentExecutionContext context,
                                         List<ReasoningStep> history,
                                         ReasoningStep step,
                                         int llmSchemaErrorCount) {

        FinishActionHandler.FinishOutcome outcome = finishActionHandler.handle(
                context.sessionId(), context.agentRole(), history, step, llmSchemaErrorCount);

        return switch (outcome) {
            case FinishActionHandler.FinishOutcome.Completed c -> {
                // §3.6: writer.saveAnswer() belongs here, NOT inside FinishActionHandler
                if (context.sessionId() != null && c.answer() != null && !c.answer().isBlank()) {
                    writer.saveAnswer(context.sessionId(), c.answer());
                }

                // Add the finish step itself to history so it appears in AgentResult
                history.add(step.withObservation("Task completed."));

                // Synthesizer path logic integrated into loop
                if (AgentRoleEnum.SYNTHESIZER.toScopedName().equals(context.agentRole())) {
                    yield new FinishDecision(handleSynthesizerFinish(history, c.answer()), null);
                }

                // Re-create the result with the updated history
                AgentResult finalResult = (c.result() instanceof AgentResult.Finished f)
                    ? AgentResult.finished(f.answer(), List.copyOf(history))
                    : c.result();

                yield new FinishDecision(finalResult, null);
            }
            case FinishActionHandler.FinishOutcome.RetryLeak rl -> {
                // Add cleaned step to history and let caller continue the loop
                history.add(rl.correctedStep());
                yield new FinishDecision(null, rl.correctionHint());
            }
            case FinishActionHandler.FinishOutcome.SalvageFailed sf -> {
                 // Add the failed finish step to history
                 history.add(step.withObservation("Salvage failed."));
                 AgentResult failedResult = (sf.result() instanceof AgentResult.Failed f)
                     ? AgentResult.failed(f.reason(), f.category(), List.copyOf(history))
                     : sf.result();
                 yield new FinishDecision(failedResult, null);
            }
        };
    }

    private AgentResult handleSynthesizerFinish(List<ReasoningStep> history, String answer) {
        String combinedContext = history.stream()
                .filter(s -> s.observation() != null && !s.observation().isBlank()
                        && (s.success() == null || s.success())
                        && !"Task completed.".equals(s.observation()))
                .map(s -> "Thought: " + s.thought() + "\n" + s.observation())
                .collect(Collectors.joining("\n"));

        // If no combined context, just return the answer as-is
        if (combinedContext.isBlank()) {
            return AgentResult.finished(answer, List.copyOf(history));
        }

        // Synthesizer has useful prior observations — return enriched answer
        return AgentResult.finished(answer, List.copyOf(history));
    }
}
