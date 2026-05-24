package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.lifecycle.WorkspaceManager;
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

        while (true) {
            if (Thread.currentThread().isInterrupted() || WorkspaceManager.isSessionHalted(context.sessionId())) {
                log.warnf("[StepRouter] session %s: halted during routing", context.sessionId());
                return RoutingOutcome.proceedToNextTurn(consecutiveFailures);
            }
            if (currentStep == null) {
                log.warnf("[StepRouter] session %s: reached routing logic with null step.", context.sessionId());
                return RoutingOutcome.proceedToNextTurn(consecutiveFailures);
            }

            if (currentStep instanceof ReasoningStep.FinishStep finish) {
                Optional<RoutingOutcome> finishOutcome = handleFinishOutcome(context, history, finish, llmSchemaErrorCount);
                if (finishOutcome.isPresent()) return finishOutcome.get();
            }

            StepDecision validationDecision = toolStepHandler.validateTool(context, history, currentStep, consecutiveFailures);

            if (validationDecision.type() == DecisionType.TERMINAL) {
                return RoutingOutcome.terminal(validationDecision.result());
            }
            
            if (validationDecision.isRetryValidation()) {
                currentStep = validationDecision.step();
                consecutiveFailures = validationDecision.consecutiveFailures();
                continue; 
            }

            if (validationDecision.type() == DecisionType.CONTINUE) {
                 return RoutingOutcome.proceedToNextTurn(validationDecision.consecutiveFailures());
            }

            currentStep = validationDecision.step();

            if (currentStep instanceof ReasoningStep.FinishStep) {
                continue; 
            }

            return handleToolInvocation(context, history, currentStep, consecutiveFailures, skillSnapshot);
        }
    }

    private Optional<RoutingOutcome> handleFinishOutcome(AgentExecutionContext context,
                                                         List<ReasoningStep> history,
                                                         ReasoningStep.FinishStep currentStep,
                                                         int llmSchemaErrorCount) {
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
            return Optional.of(RoutingOutcome.terminal(res));
        } else if (outcome instanceof FinishActionHandler.FinishOutcome.Correction correction) {
            history.add(correction.correctedStep());
            return Optional.of(RoutingOutcome.retryEngine(correction.correctionHint(), llmSchemaErrorCount + 1));
        } else if (outcome instanceof FinishActionHandler.FinishOutcome.RetryLeak rl) {
            history.add(rl.correctedStep());
            return Optional.of(RoutingOutcome.retryEngine(rl.correctionHint(), llmSchemaErrorCount + 1));
        } else if (outcome instanceof FinishActionHandler.FinishOutcome.SalvageFailed sf) {
            history.add(currentStep.withObservation("Salvage failed."));
            AgentResult failedResult = (sf.result() instanceof AgentResult.Failed f)
                ? AgentResult.failed(f.reason(), f.category(), List.copyOf(history))
                : sf.result();
            return Optional.of(RoutingOutcome.terminal(failedResult));
        } else if (outcome instanceof FinishActionHandler.FinishOutcome.ExecuteLeakedTool elt) {
            log.infof("LEAKED-TOOL-EXEC session %s: executing parsed tool '%s' from leaked FINISH answer",
                    context.sessionId(), elt.toolName());
            ReasoningStep.ToolCallStep syntheticStep = new ReasoningStep.ToolCallStep(
                    "Executing leaked tool call from FINISH answer",
                    elt.toolName(),
                    elt.arguments(),
                    null, 0, 0, null, null, null, null, false, false);
            history.add(elt.correctedStep());
            RoutingOutcome invocationResult = handleToolInvocation(context, history, syntheticStep, 0, List.of());
            return Optional.of(invocationResult);
        } else if (outcome instanceof FinishActionHandler.FinishOutcome.InjectToolCall itc) {
            log.infof("TOOL-INJECT session %s: injecting tool '%s' that agent missed",
                    context.sessionId(), itc.toolName());
            ReasoningStep.ToolCallStep syntheticStep = new ReasoningStep.ToolCallStep(
                    "Injected tool call — agent finished without using available tools",
                    itc.toolName(),
                    itc.arguments(),
                    null, 0, 0, null, null, null, null, false, false);
            history.add(itc.correctedStep());
            RoutingOutcome invocationResult = handleToolInvocation(context, history, syntheticStep, 0, List.of());
            return Optional.of(invocationResult);
        }
        return Optional.empty();
    }

    private RoutingOutcome handleToolInvocation(AgentExecutionContext context,
                                                List<ReasoningStep> history,
                                                ReasoningStep currentStep,
                                                int consecutiveFailures,
                                                List<CompiledSkillEntry> skillSnapshot) {
        if (context.isStatusDirty()) {
            var cpOpt = engineStepHandler.loadCheckpointStatus(context);
            context.setLastCheckpointStatus(cpOpt.orElse(null));
            if (context.lastCheckpointStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                // Retry once after a short delay — the claimForResume() transaction may have
                // committed in a different thread but WAL visibility is not yet guaranteed.
                try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                context.markStatusDirty(); // force re-read from DB
                cpOpt = engineStepHandler.loadCheckpointStatus(context);
                context.setLastCheckpointStatus(cpOpt.orElse(null));
                if (context.lastCheckpointStatus() == CheckpointStatus.AWAITING_APPROVAL) {
                    log.warnf("Session %s is still AWAITING_APPROVAL after retry. Refusing to invoke tool.", context.sessionId());
                    return RoutingOutcome.terminal(AgentResult.failed("State corrupted - tool execution attempted before approval persistence completed.",
                            AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
                }
                log.infof("Session %s: AWAITING_APPROVAL resolved to %s after WAL visibility retry.", context.sessionId(), context.lastCheckpointStatus());
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
