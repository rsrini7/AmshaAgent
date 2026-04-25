package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.IntentClassificationService;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
    private final SkillRegistry skillRegistry;
    private final InputSanitizer piiScanReflex;
    private final PiiRedactionTransformer piiTransformer;
    private final AgentOtelContext agentOtelContext;
    private final IntentClassificationService intentDetector;
    private final LoopExitPublisher loopExitPublisher;
    private final FinishActionHandler finishActionHandler;
    private final CorrectionStrategy correctionStrategy;
    private final int maxRateLimitRetries;

    @Inject
    public AgentStateMachineRunner(EngineStepHandler engineStepHandler,
                                   ToolStepHandler toolStepHandler,
                                   ChatMemoryWriter writer,
                                   Instance<AgentObserver> observers,
                                   SkillRegistry skillRegistry,
                                   @InputSanitizer.PII InputSanitizer piiScanReflex,
                                   PiiRedactionTransformer piiTransformer,
                                   AgentOtelContext agentOtelContext,
                                   IntentClassificationService intentDetector,
                                   LoopExitPublisher loopExitPublisher,
                                   FinishActionHandler finishActionHandler,
                                   CorrectionStrategy correctionStrategy,
                                   @ConfigProperty(name = "arcclaw.agent.max-ratelimit-retries", defaultValue = "3") int maxRateLimitRetries) {
        this.engineStepHandler = engineStepHandler;
        this.toolStepHandler = toolStepHandler;
        this.writer = writer;
        this.observers = observers;
        this.skillRegistry = skillRegistry;
        this.piiScanReflex = piiScanReflex;
        this.piiTransformer = piiTransformer;
        this.agentOtelContext = agentOtelContext;
        this.intentDetector = intentDetector;
        this.loopExitPublisher = loopExitPublisher;
        this.finishActionHandler = finishActionHandler;
        this.correctionStrategy = correctionStrategy;
        this.maxRateLimitRetries = maxRateLimitRetries;
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
        int iteration = context.startingIteration();
        int llmSchemaErrorCount = 0;   // plain int — NEVER AtomicInteger (§3.7)
        int consecutiveFailures = 0;
        int rateLimitRetryCount = 0;
        String transientError = null;  // written by write-intent injection AND correction paths

        String originalGoal = context.goal() != null ? context.goal() : "unknown";
        
        // --- 1. PII Redaction & Goal Preparation ---
        String goal = piiTransformer != null ? piiTransformer.transform(null, originalGoal) : originalGoal;
        if (goal == null) goal = originalGoal;
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

        // Privacy-first (§7.35): saves the PII-redacted goal to ChatMemory.
        // REDACTED-* placeholder tokens will appear in reconstructed conversation
        // history. The original unredacted goal is preserved in context.goal
        // for internal use only and is never written to ChatMemory.
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

                    // --- Write-intent hint injection (initial start only) ---
                    // §7.45: Only inject the hint if history is empty. If we are resuming,
                    // history contains the tool call/result already.
                    if (history.isEmpty() && intentDetector.isWriteIntent(context.goal())) {
                        transientError = "The user wants to store data. Call ingest_document immediately. "
                                + "Pass REDACTED-* placeholders as-is. No explanation needed.";
                    }

                    // --- Engine step ---
                    StepDecision engineDecision;
                    try {
                        engineDecision = engineStepHandler.processEngineStep(
                                context, engine, history, transientError, llmSchemaErrorCount, iteration);

                        // §Issue 3: Rate-limit retries are "free" iterations (up to a limit)
                        if (engineDecision.isRateLimitHit()) {
                            if (++rateLimitRetryCount > maxRateLimitRetries) {
                                log.warnf("StateMachine rate limit retries exhausted (%d) for session %s", 
                                        maxRateLimitRetries, context.sessionId());
                                finalResult = AgentResult.failed("I hit repeated rate limits and cannot continue.", 
                                        AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                                break;
                            }
                            iteration--;
                        }
                    } catch (StructuredOutputException soe) {
                        // Delegate schema-error handling to CorrectionStrategy
                        if (!correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                            log.warnf("StateMachine schema self-correction exhausted for session %s", context.sessionId());
                            finalResult = AgentResult.loopExceeded(
                                    context.maxIterations(), List.copyOf(history));
                            // loopExitPublisher fires in finally — see first TERMINAL comment above.
                            return finalResult;
                        }

                        llmSchemaErrorCount++;
                        transientError = correctionStrategy.buildCorrectionHint(llmSchemaErrorCount, soe.getMessage());
                        // → re-invokes LLM for a new thought on the next iteration (isRetryValidation=false)
                        log.warnf("StateMachine schema self-correction attempt %d for session %s",
                                llmSchemaErrorCount, context.sessionId());
                        continue;
                    }

                    // --- Terminal decision ---
                    // loopExitPublisher.publish() is called unconditionally in the finally
                    // block below — do not call it here. The publisher is idempotent for the
                    // same context+result pair (see LoopExitPublisher contract): publishing
                    // twice for the same session is safe but the finally path is the single
                    // authoritative publish point in this runner.
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
                            
                            FinishActionHandler.FinishOutcome outcome = finishActionHandler.handle(
                                    context, history, step, llmSchemaErrorCount);

                            if (outcome instanceof FinishActionHandler.FinishOutcome.Completed c) {
                                context.setForceFinishRetry(false); // clear — no longer needed
                                // §3.6: writer.saveAnswer() belongs here, NOT inside FinishActionHandler
                                if (context.sessionId() != null && c.answer() != null && !c.answer().isBlank()) {
                                    writer.saveAnswer(context.sessionId(), c.answer());
                                }
                                history.add(step.withObservation("Task completed."));
                                
                                AgentResult res = c.result();
                                if (AgentRoleEnum.SYNTHESIZER.toScopedName().equals(context.agentRole())) {
                                    // Synthesizer has useful prior observations
                                    res = AgentResult.finished(c.answer(), List.copyOf(history));
                                } else if (res instanceof AgentResult.Finished f) {
                                    res = AgentResult.finished(f.answer(), List.copyOf(history));
                                }
                                finalResult = res;
                                // loopExitPublisher fires in finally — see first TERMINAL comment above.
                                return finalResult;
                            } else if (outcome instanceof FinishActionHandler.FinishOutcome.Correction correction) {
                                log.warnf("STATE-MACHINE correction required in session %s — retrying with hint",
                                        context.sessionId());
                                history.add(correction.correctedStep());
                                transientError = correction.correctionHint();
                                llmSchemaErrorCount++;
                                break; // exit validation loop to retry engine
                            } else if (outcome instanceof FinishActionHandler.FinishOutcome.RetryLeak _) {
                                log.warnf("STATE-MACHINE raw tool-call leak detected in session %s — retrying with hint",
                                        context.sessionId());
                                llmSchemaErrorCount++;
                                break; // exit validation loop to retry engine
                            } else if (outcome instanceof FinishActionHandler.FinishOutcome.SalvageFailed sf) {
                                history.add(step.withObservation("Salvage failed."));
                                AgentResult failedResult = (sf.result() instanceof AgentResult.Failed f)
                                    ? AgentResult.failed(f.reason(), f.category(), List.copyOf(history))
                                    : sf.result();
                                finalResult = failedResult;
                                // loopExitPublisher fires in finally — see first TERMINAL comment above.
                                return finalResult;
                            }
                        }

                        // --- Tool validation ---
                        StepDecision validationDecision = toolStepHandler.validateTool(
                                context, history, step, consecutiveFailures);

                        // loopExitPublisher.publish() is called unconditionally in the finally
                        // block below — do not call it here. The publisher is idempotent for the
                        // same context+result pair (see LoopExitPublisher contract): publishing
                        // twice for the same session is safe but the finally path is the single
                        // authoritative publish point in this runner.
                        if (validationDecision.type() == DecisionType.TERMINAL) {
                            finalResult = validationDecision.result();
                            return finalResult;
                        }
                        
                        if (validationDecision.isRetryValidation()) {
                            consecutiveFailures = validationDecision.consecutiveFailures();
                            // Deduplication logic: validationDecision added the dedup step to history
                            // → skips LLM; re-enters inner validation loop immediately (isRetryValidation=true)
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
                            FinishActionHandler.FinishOutcome outcome = finishActionHandler.handle(
                                    context, history, step, llmSchemaErrorCount);
                            if (outcome instanceof FinishActionHandler.FinishOutcome.Completed c) {
                                finalResult = c.result();
                                // loopExitPublisher fires in finally — see first TERMINAL comment above.
                                return finalResult;
                            } else if (outcome instanceof FinishActionHandler.FinishOutcome.Correction correction) {
                                history.add(correction.correctedStep());
                                transientError = correction.correctionHint();
                                llmSchemaErrorCount++;
                            } else if (outcome instanceof FinishActionHandler.FinishOutcome.RetryLeak rl) {
                                history.add(rl.correctedStep());
                                transientError = rl.correctionHint();
                                llmSchemaErrorCount++;
                            }
                            // Other outcomes (leak, salvage) follow same retry/terminal path as above
                            break; 
                        }

                        // Validation passed, proceed to invocation
                        // --- Tool invocation ---
                        // Fetch skill snapshot lazily
                        var skillSnapshot = skillRegistry.currentSnapshot();

                        // HITL State Check
                        if (context.isStatusDirty()) {
                            var cpOpt = engineStepHandler.loadCheckpointStatus(context);
                            context.setLastCheckpointStatus(cpOpt.orElse(null));
                            if (context.lastCheckpointStatus() == CheckpointStatus.AWAITING_APPROVAL) {                                log.warnf("Session %s is still AWAITING_APPROVAL. Refusing to invoke tool "
                                        + "until state is flushed as IN_PROGRESS.", context.sessionId());
                                finalResult = AgentResult.failed("State corrupted — tool execution attempted before approval persistence completed.",
                                        AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
                                // loopExitPublisher fires in finally — see first TERMINAL comment above.
                                return finalResult;
                            }
                        }

                        // NOTE: ToolStepHandler.invokeToolAndObserve() is responsible for adding
                        // the completed step to history. Do NOT add step to history here.
                        StepDecision invocationDecision = toolStepHandler.invokeToolAndObserve(
                                context, history, step, consecutiveFailures, skillSnapshot);

                        if (invocationDecision.type() == DecisionType.TERMINAL) {
                            finalResult = invocationDecision.result();
                            // loopExitPublisher fires in finally — see first TERMINAL comment above.
                            return finalResult;
                        }

                        if (invocationDecision.result() instanceof AgentResult.AwaitingApproval) {
                            context.markStatusDirty();
                            finalResult = invocationDecision.result();
                            // loopExitPublisher fires in finally — see first TERMINAL comment above.
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
            // loopExitPublisher fires in finally — see first TERMINAL comment above.
            return finalResult;

        // finalResult is set before return. The finally block below calls
        // loopExitPublisher.publish(context, res) exactly once — no explicit
        // publish needed here. finally always executes before the return exits
        // this method (JLS §14.20.2).
        } catch (BudgetExceededException e) {
            finalResult = AgentResult.failed(e.getMessage(), AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(history));
            return finalResult;
        } catch (Exception e) {
            log.errorf("StateMachine terminal failure for session %s: %s", context.sessionId(), e.getMessage());
            finalResult = AgentResult.failed(e.getMessage(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
            throw e;
        } finally {
            AgentResult res = finalResult;
            
            log.debugf("Loop complete for role %s", context.agentRole());

            loopExitPublisher.publish(context, res);
            observers.forEach(o -> {
                o.onLoopComplete(context.sessionId(), context.userId(), context.agentRole(), goalHash, res);
            });
        }
    }
}
