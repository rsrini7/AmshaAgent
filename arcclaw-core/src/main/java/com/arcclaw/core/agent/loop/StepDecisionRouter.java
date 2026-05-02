package com.arcclaw.core.agent.loop;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.state.CheckpointStatus;
import org.jboss.logging.Logger;

/**
 * Handles decision routing for engine and tool steps within the agent state machine.
 * This class encapsulates the complex branching logic for FINISH actions, tool validation,
 * and invocation outcomes, keeping the main runner lean.
 */
@ApplicationScoped
public class StepDecisionRouter {

    private static final Logger log = Logger.getLogger(StepDecisionRouter.class);

    private final FinishActionHandler finishActionHandler;
    private final ToolStepHandler toolStepHandler;
    private final ChatMemoryWriter writer;
    private final EngineStepHandler engineStepHandler;

    @Inject
    public StepDecisionRouter(FinishActionHandler finishActionHandler,
                              ToolStepHandler toolStepHandler,
                              ChatMemoryWriter writer,
                              EngineStepHandler engineStepHandler) {
        this.finishActionHandler = finishActionHandler;
        this.toolStepHandler = toolStepHandler;
        this.writer = writer;
        this.engineStepHandler = engineStepHandler;
    }

    /**
     * Represents the routing outcome for a single step.
     */
    public record RoutingOutcome(
            AgentResult result,
            ReasoningStep nextStep,
            String transientError,
            Integer llmSchemaErrorCount,
            Integer consecutiveFailures,
            boolean retryEngine,
            boolean isTerminal
    ) {
        public static RoutingOutcome terminal(AgentResult result) {
            return new RoutingOutcome(result, null, null, null, null, false, true);
        }

        public static RoutingOutcome retryEngine(String error, int schemaErrors) {
            return new RoutingOutcome(null, null, error, schemaErrors, null, true, false);
        }

        public static RoutingOutcome proceedToNextTurn(int failures) {
            return new RoutingOutcome(null, null, null, null, failures, false, false);
        }
    }

    public RoutingOutcome route(AgentExecutionContext context,
                                List<ReasoningStep> history,
                                ReasoningStep step,
                                int llmSchemaErrorCount,
                                int consecutiveFailures,
                                List<CompiledSkillEntry> skillSnapshot) {
        
        ReasoningStep currentStep = step;

        // Validation Loop (to support Deduplication retries without using a turn)
        while (true) {
            if (currentStep == null) {
                log.warnf("[StepRouter] session %s: reached routing logic with null step.", context.sessionId());
                return RoutingOutcome.proceedToNextTurn(consecutiveFailures);
            }

            // --- 1. FINISH action ---
            if (ReasoningStep.FINISH.equals(currentStep.action())) {
                if (currentStep.isParallelBatch()) {
                    log.errorf("PARALLEL INVARIANT VIOLATION: session %s parallel batch step reached FINISH handler.", context.sessionId());
                }
                
                FinishActionHandler.FinishOutcome outcome = finishActionHandler.handle(context, history, currentStep, llmSchemaErrorCount);

                if (outcome instanceof FinishActionHandler.FinishOutcome.Completed c) {
                    context.setForceFinishRetry(false);
                    if (context.sessionId() != null && c.answer() != null && !c.answer().isBlank()) {
                        writer.saveAnswer(context.sessionId(), c.answer());
                    }
                    history.add(currentStep.withObservation("Task completed."));
                    
                    AgentResult res = c.result();
                    if (AgentRoleEnum.SYNTHESIZER.toScopedName().equals(context.agentRole())) {
                        res = AgentResult.finished(c.answer(), List.copyOf(history));
                    } else if (res instanceof AgentResult.Finished f) {
                        res = AgentResult.finished(f.answer(), List.copyOf(history));
                    }
                    return RoutingOutcome.terminal(res);
                } else if (outcome instanceof FinishActionHandler.FinishOutcome.Correction correction) {
                    history.add(correction.correctedStep());
                    return RoutingOutcome.retryEngine(correction.correctionHint(), llmSchemaErrorCount + 1);
                } else if (outcome instanceof FinishActionHandler.FinishOutcome.RetryLeak rl) {
                    history.add(rl.correctedStep());
                    return RoutingOutcome.retryEngine(rl.correctionHint(), llmSchemaErrorCount + 1);
                } else if (outcome instanceof FinishActionHandler.FinishOutcome.SalvageFailed sf) {
                    history.add(currentStep.withObservation("Salvage failed."));
                    AgentResult failedResult = (sf.result() instanceof AgentResult.Failed f)
                        ? AgentResult.failed(f.reason(), f.category(), List.copyOf(history))
                        : sf.result();
                    return RoutingOutcome.terminal(failedResult);
                }
            }

            // --- 2. Tool validation ---
            StepDecision validationDecision = toolStepHandler.validateTool(context, history, currentStep, consecutiveFailures);

            if (validationDecision.type() == DecisionType.TERMINAL) {
                return RoutingOutcome.terminal(validationDecision.result());
            }
            
            if (validationDecision.isRetryValidation()) {
                // Deduplication logic: re-enters inner validation loop immediately
                currentStep = validationDecision.step();
                consecutiveFailures = validationDecision.consecutiveFailures();
                continue; 
            }

            if (validationDecision.type() == DecisionType.CONTINUE) {
                 return RoutingOutcome.proceedToNextTurn(validationDecision.consecutiveFailures());
            }

            currentStep = validationDecision.step();

            // Re-dispatch if validateTool rewrote a hallucinated tool to FINISH
            if (ReasoningStep.FINISH.equals(currentStep.action())) {
                continue; // Re-enters loop to handle FINISH branch
            }

            // --- 3. Tool invocation ---
            if (context.isStatusDirty()) {
                var cpOpt = engineStepHandler.loadCheckpointStatus(context);
                context.setLastCheckpointStatus(cpOpt.orElse(null));
                if (context.lastCheckpointStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                    log.warnf("Session %s is still AWAITING_APPROVAL. Refusing to invoke tool until state is flushed as IN_PROGRESS.", context.sessionId());
                    return RoutingOutcome.terminal(AgentResult.failed("State corrupted - tool execution attempted before approval persistence completed.",
                            AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
                }
            }

            StepDecision invocationDecision = toolStepHandler.invokeToolAndObserve(context, history, currentStep, consecutiveFailures, skillSnapshot);

            if (invocationDecision.type() == DecisionType.TERMINAL) {
                return RoutingOutcome.terminal(invocationDecision.result());
            }

            if (invocationDecision.result() instanceof AgentResult.AwaitingApproval) {
                context.markStatusDirty();
                return RoutingOutcome.terminal(invocationDecision.result());
            }

            return RoutingOutcome.proceedToNextTurn(invocationDecision.consecutiveFailures());
        }
    }
}
