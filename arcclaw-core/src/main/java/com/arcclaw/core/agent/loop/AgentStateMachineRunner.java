package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.persona.Agent;
import com.arcclaw.core.agent.persona.AgentRole;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.reflex.PiiScanReflex;
import com.arcclaw.core.skill.CompiledSkillEntry;
import com.arcclaw.core.skill.SkillRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.jboss.logging.Logger;

/**
 * Orchestrates the Thought-Plan-Act-Observe loop.
 * Step-level processing is delegated to {@link AgentStepProcessor}.
 */
@ApplicationScoped
public class AgentStateMachineRunner {
    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    @Inject AgentStepProcessor stepProcessor;
    @Inject ChatMemoryWriter writer;
    @Inject Instance<AgentObserver> observers;
    @Inject @AgentRole("synthesizer") Agent synthesizerAgent;
    @Inject SkillRegistry skillRegistry;
    @Inject PiiScanReflex piiScanReflex;
    @Inject AgentOtelContext agentOtelContext;

    public AgentResult run(AgentExecutionContext context, ReasoningEngine engine,
                           List<ReasoningStep> resumedHistory) {
        List<ReasoningStep> history = new ArrayList<>(resumedHistory);
        int iteration = 0;
        int llmSchemaErrorCount = 0;
        int consecutiveFailures = 0;
        String transientError = null;

        log.infof("StateMachine starting session=%s goal=%s maxIterations=%d",
                context.sessionId(), piiScanReflex.redactPii(context.goal()), context.maxIterations());

        writer.saveGoal(context.sessionId(), context.goal());

        while (iteration < context.maxIterations()) {
            iteration++;
            Span iterSpan = agentOtelContext.startIterationSpan(
                    context.sessionId(), iteration, context.agentRole());
            try (Scope scope = iterSpan.makeCurrent()) {
                log.debugf("StateMachine iteration %d/%d", iteration, context.maxIterations());

                List<CompiledSkillEntry> skillSnapshot = skillRegistry.currentSnapshot();

                StepDecision engineDecision = stepProcessor.processEngineStep(
                        context.sessionId(), context.goal(), engine, history,
                        context.agentRole(), transientError, llmSchemaErrorCount, iteration);

                if (engineDecision.type() == DecisionType.TERMINAL) return engineDecision.result();
                if (engineDecision.type() == DecisionType.CONTINUE) {
                    transientError = engineDecision.transientError();
                    llmSchemaErrorCount = engineDecision.llmSchemaErrorCount();
                    continue;
                }

                ReasoningStep step = engineDecision.step();
                transientError = null;

                if (ReasoningStep.FINISH.equals(step.action()))
                    return handleFinishAction(context.sessionId(), context.agentRole(),
                            history, step, iteration);

                StepDecision validationDecision = stepProcessor.validateTool(
                        context.sessionId(), context.agentRole(), history,
                        step, context.goal(), consecutiveFailures);
                if (validationDecision.type() == DecisionType.TERMINAL) return validationDecision.result();
                if (validationDecision.type() == DecisionType.CONTINUE) {
                    consecutiveFailures = validationDecision.consecutiveFailures();
                    continue;
                }
                step = validationDecision.step();

                StepDecision invocationDecision = stepProcessor.invokeToolAndObserve(
                        context.sessionId(), context.agentRole(), history, step,
                        consecutiveFailures, context.goal(), skillSnapshot);
                if (invocationDecision.type() == DecisionType.TERMINAL) return invocationDecision.result();
                consecutiveFailures = invocationDecision.consecutiveFailures();

            } catch (Exception e) {
                iterSpan.recordException(e);
                iterSpan.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                iterSpan.end();
            }
        }

        log.warnf("StateMachine exceeded %d iterations for session=%s",
                context.maxIterations(), context.sessionId());
        return AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
    }

    private AgentResult handleFinishAction(String sessionId, String agentRole,
            List<ReasoningStep> history, ReasoningStep step, int iteration) {
        log.infof("Agent reached FINISH at iteration %d", iteration);
        String answer = step.thought();
        if (step.actionInput() != null && step.actionInput().containsKey("answer")) {
            answer = String.valueOf(step.actionInput().get("answer"));
        }

        if (answer != null && isGeneratedCodeBlock(answer)) {
            if (AgentRoleEnum.RESEARCHER.toScopedName().equals(agentRole)
                    || AgentRoleEnum.ARCHITECT.toScopedName().equals(agentRole)) {
                String refusal = "I'm " + agentRole + " — I don't generate code. " +
                        "Please ask Brahma (coder) for code generation tasks.";
                writer.saveAnswer(sessionId, refusal);
                history.add(step.withObservation("Blocked: code generation by non-coder role"));
                return AgentResult.finished(refusal, List.copyOf(history));
            }
        }

        if (sessionId != null && answer != null && !answer.isBlank()) {
            writer.saveAnswer(sessionId, answer);
        }

        final ReasoningStep completedStep = step.withObservation("Task completed.");
        history.add(completedStep);
        observers.forEach(o -> o.onStepComplete(sessionId, agentRole, completedStep));

        if (AgentRoleEnum.SYNTHESIZER.toScopedName().equals(agentRole)) {
            String combinedContext = history.stream()
                    .filter(s -> s.observation() != null 
                            && !s.observation().isBlank()
                            && !"Task completed.".equals(s.observation()))  // skip trivial
                    .map(s -> "Thought: " + s.thought() + "\nFinding: " + s.observation())
                    .collect(Collectors.joining("\n\n"));

            if (combinedContext.isBlank()) {
                // No real multi-agent findings — return direct answer as-is
                return AgentResult.finished(answer, List.copyOf(history));
            }

            return synthesizerAgent.synthesize(sessionId, combinedContext, history);
        }

        return AgentResult.finished(answer, List.copyOf(history));
    }

    private boolean isGeneratedCodeBlock(String text) {
        if (text == null) return false;
        String stripped = text.stripLeading();
        if (stripped.startsWith("```")) return true;
        if (stripped.matches("(?si)here[''']?s a .{0,60}(program|function|class|"
                + "method|algorithm|implementation|snippet|code).*```.*")) return true;
        if (stripped.matches("(?si)here is a .{0,60}(program|function|class|"
                + "method|algorithm|implementation|snippet|code).*```.*")) return true;
        return false;
    }

    // ── Shared types ─────────────────────────────────────────────────────────
    // Package-private so AgentStepProcessor can reference them without a
    // circular dependency — both live in com.arcclaw.core.agent.loop.

    enum DecisionType { CONTINUE, TERMINAL, PROCEED }

    record StepDecision(
            DecisionType type,
            AgentResult result,
            ReasoningStep step,
            String transientError,
            int llmSchemaErrorCount,
            int consecutiveFailures
    ) {
        static StepDecision proceed(ReasoningStep step) {
            return new StepDecision(DecisionType.PROCEED, null, step, null, 0, 0);
        }
        static StepDecision terminal(AgentResult result) {
            return new StepDecision(DecisionType.TERMINAL, result, null, null, 0, 0);
        }
        static StepDecision retryEngine(String transientError, int llmSchemaErrorCount) {
            return new StepDecision(DecisionType.CONTINUE, null, null, transientError, llmSchemaErrorCount, 0);
        }
        static StepDecision retryValidation(int consecutiveFailures) {
            return new StepDecision(DecisionType.CONTINUE, null, null, null, 0, consecutiveFailures);
        }
        static StepDecision proceedWithFailures(ReasoningStep step, int consecutiveFailures) {
            return new StepDecision(DecisionType.PROCEED, null, step, null, 0, consecutiveFailures);
        }
    }
}
