package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.task.TaskManager;
import com.amsha.core.util.AgentErrorMessageUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * Orchestrates the Thought-Plan-Act-Observe loop.
 */
@ApplicationScoped
public class AgentStateMachineRunner {

    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    @Inject
    TaskClassifierService classifierService;

    @Inject
    TaskManager taskManager;

    private static final int LOG_GOAL_MAX = 80;
    private static final String GOAL_LOG_FMT = "StateMachine starting session=%s goal='%s' maxIterations=%d";

    private final EngineStepHandler engineStepHandler;
    private final ChatMemoryWriter writer;
    private final Instance<AgentObserver> observers;
    private final SkillRegistry skillRegistry;
    private final AgentOtelContext agentOtelContext;
    private final IntentClassificationService intentDetector;
    private final LoopExitPublisher loopExitPublisher;
    private final FinishActionHandler finishActionHandler;
    private final CorrectionStrategy correctionStrategy;
    private final int maxRateLimitRetries;
    private final MeterRegistry meterRegistry;
    private final StepDecisionRouter stepDecisionRouter;
    private final BrowserPreflightHandler browserPreflightHandler;

    @Inject
    public AgentStateMachineRunner(
            EngineStepHandler engineStepHandler,
            ChatMemoryWriter writer,
            Instance<AgentObserver> observers,
            SkillRegistry skillRegistry,
            AgentOtelContext agentOtelContext,
            IntentClassificationService intentDetector,
            LoopExitPublisher loopExitPublisher,
            FinishActionHandler finishActionHandler,
            CorrectionStrategy correctionStrategy,
            @ConfigProperty(name = "amsha.agent.max-ratelimit-retries", defaultValue = "3") int maxRateLimitRetries,
            MeterRegistry meterRegistry,
            StepDecisionRouter stepDecisionRouter,
            BrowserPreflightHandler browserPreflightHandler) {
        this.engineStepHandler = engineStepHandler;
        this.writer = writer;
        this.observers = observers;
        this.skillRegistry = skillRegistry;
        this.agentOtelContext = agentOtelContext;
        this.intentDetector = intentDetector;
        this.loopExitPublisher = loopExitPublisher;
        this.finishActionHandler = finishActionHandler;
        this.correctionStrategy = correctionStrategy;
        this.maxRateLimitRetries = maxRateLimitRetries;
        this.meterRegistry = meterRegistry;
        this.stepDecisionRouter = stepDecisionRouter;
        this.browserPreflightHandler = browserPreflightHandler;
    }

    /**
     * Performs a single terminal synthesis pass (FINISH) after a tool has been
     * executed and approved during resumption.
     */
    public AgentResult runFinishPass(
            AgentExecutionContext context, ReasoningEngine engine, List<ReasoningStep> history) {
        log.infof("StateMachine: performing terminal finish pass for session %s", context.sessionId());
        context.setForceFinishRetry(true);

        int historySizeBeforeTerminalHint = history.size();
        history.add(
                ReasoningStep.systemMessage("CRITICAL TERMINAL MODE: The tool above has been executed successfully. "
                        + "You are in the final synthesis pass. Your ONLY permitted action is FINISH. "
                        + "Do NOT call any more tools. Provide a brief plain-text summary of what was accomplished."));

        int offset = context.getCheckpointRestoreOffset();
        ReasoningStep lastToolStep = history.stream()
                .skip(offset)
                .filter(s -> (ToolCapability.SANDBOX_EXEC.toolName().equals(s.action())
                                || ToolCapability.HOST_EXEC.toolName().equals(s.action()))
                        && s.observation() != null
                        && !s.observation().isBlank())
                .reduce((first, second) -> second)
                .orElse(null);

        boolean currentTurnHasToolAttempt = history.stream()
                .skip(offset)
                .anyMatch(s -> ToolCapability.SANDBOX_EXEC.toolName().equals(s.action())
                        || ToolCapability.HOST_EXEC.toolName().equals(s.action()));

        if (lastToolStep != null && currentTurnHasToolAttempt) {
            history.add(ReasoningStep.systemMessage("The " + lastToolStep.action()
                    + " tool returned the following output. Report it EXACTLY:\n" + "OUTPUT: "
                    + lastToolStep.observation() + "\n"
                    + "Do NOT paraphrase, recalculate, or invent values. Use the exact value above."));
        }

        try {
            ReasoningStep step = engine.nextStep(context, context.agentRole(), context.goal(), history);
            if (step instanceof ReasoningStep.FinishStep finish) {
                FinishActionHandler.FinishOutcome outcome = finishActionHandler.handle(context, history, finish, 0);

                if (outcome instanceof FinishActionHandler.FinishOutcome.Completed c) {
                    if (context.sessionId() != null
                            && c.answer() != null
                            && !c.answer().isBlank()) {
                        writer.saveAnswer(context.sessionId(), c.answer());
                    }
                    return c.result();
                }
            }

            log.warnf(
                    "StateMachine: terminal pass did not result in FINISH for session %s; retrying with guidance hint.",
                    context.sessionId());
            int historySizeBeforeHint = history.size();
            history.add(
                    ReasoningStep.systemMessage(
                            "CRITICAL: You have already executed the tool successfully. Do NOT call any more tools. "
                                    + "Your ONLY remaining action is to FINISH with a plain text summary of what you did. "
                                    + "Provide the summary directly as natural language prose — no JSON, no XML, no code blocks. "
                                    + "Even a short sentence like 'Information stored successfully' is acceptable. Finish now."));

            try {
                ReasoningStep retryStep = engine.nextStep(context, context.agentRole(), context.goal(), history);
                if (retryStep instanceof ReasoningStep.FinishStep retryFinish) {
                    FinishActionHandler.FinishOutcome retryOutcome =
                            finishActionHandler.handle(context, history, retryFinish, 0);

                    if (retryOutcome instanceof FinishActionHandler.FinishOutcome.Completed rc) {
                        if (context.sessionId() != null
                                && rc.answer() != null
                                && !rc.answer().isBlank()) {
                            writer.saveAnswer(context.sessionId(), rc.answer());
                        }
                        return rc.result();
                    }
                }
            } finally {
                if (history.size() > historySizeBeforeHint) {
                    history.subList(historySizeBeforeHint, history.size()).clear();
                }
            }

            log.errorf(
                    "StateMachine: terminal retry failed for session %s; falling back to standard loop.",
                    context.sessionId());
            context.setForceFinishRetry(false);
            return run(context, engine, history);
        } finally {
            context.setForceFinishRetry(false);
            if (history.size() > historySizeBeforeTerminalHint) {
                history.subList(historySizeBeforeTerminalHint, history.size()).clear();
            }
        }
    }

    public AgentResult run(AgentExecutionContext context, ReasoningEngine engine, List<ReasoningStep> resumedHistory) {
        context.setCheckpointRestoreOffset(resumedHistory.size());

        String goal = context.goal() != null ? context.goal() : "unknown";
        String goalHash = AgentErrorMessageUtil.hashGoal(goal);

        if (log.isInfoEnabled()) {
            String preview = goal.length() > LOG_GOAL_MAX ? goal.substring(0, LOG_GOAL_MAX) : goal;
            log.infof(GOAL_LOG_FMT, context.sessionId(), preview, context.maxIterations());
        }

        loopExitPublisher.publishStart(context);
        writer.saveGoal(context.sessionId(), goal);

        // SAW Backlog Refinement pattern: dynamically create tasks for complex coordinator goals
        // Null checks on classifierService and taskManager are required for manually-constructed test suites.
        if (classifierService != null
                && taskManager != null
                && "coordinator".equalsIgnoreCase(context.agentRole())
                && resumedHistory.isEmpty()) {
            List<TaskClassifierService.TaskDomain> domains = classifierService.classify(goal);
            if (domains.size() > 1) {
                log.infof("[BACKLOG] Complex coordinator goal detected. Refining backlog into task list...");
                List<String> domainNames = domains.stream().map(Enum::name).toList();
                String taskName = "COORDINATE (" + String.join(", ", domainNames) + "): " + goal;
                if (taskName.length() > 80) {
                    taskName = taskName.substring(0, 77) + "...";
                }
                taskManager.createTask(
                        taskName,
                        "Refined from complex coordinator goal spanning domains: " + domainNames
                                + "\n\nOriginal Goal:\n" + goal);
            }
        }

        RunnerServices services = new RunnerServices(
                engineStepHandler,
                stepDecisionRouter,
                skillRegistry,
                correctionStrategy,
                browserPreflightHandler,
                intentDetector,
                maxRateLimitRetries);
        StateContext stateContext = new StateContext(context, engine, resumedHistory);

        return runStateMachineLoop(services, stateContext, context, goalHash);
    }

    private AgentResult runStateMachineLoop(
            RunnerServices services, StateContext stateContext, AgentExecutionContext context, String goalHash) {
        try {
            executeStateMachine(services, stateContext, context);
            return stateContext.finalResult();

        } catch (BudgetExceededException e) {
            AgentResult failedResult = AgentResult.failed(
                    e.getMessage(), AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(stateContext.history()));
            stateContext.setFinalResult(failedResult);
            return failedResult;
        } catch (Exception e) {
            log.errorf(
                    "StateMachine terminal failure %s: %s\n%s",
                    LogUtil.ctx(context.sessionId(), context.agentRole()),
                    LogUtil.rootMessage(e),
                    LogUtil.trimmedStack(e));
            AgentResult failedResult = AgentResult.failed(
                    LogUtil.rootMessage(e),
                    AgentResult.FailureCategory.SYSTEM_ERROR,
                    List.copyOf(stateContext.history()));
            stateContext.setFinalResult(failedResult);
            return failedResult;
        } finally {
            AgentResult res = stateContext.finalResult();
            meterRegistry
                    .summary("amsha.agent.iterations.distribution", "role", context.agentRole())
                    .record(stateContext.iteration() - context.startingIteration());
            if (res != null) {
                loopExitPublisher.publish(context, res);
                observers.forEach(o ->
                        o.onLoopComplete(context.sessionId(), context.userId(), context.agentRole(), goalHash, res));
            }
        }
    }

    private void executeStateMachine(
            RunnerServices services, StateContext stateContext, AgentExecutionContext context) {
        Span iterSpan = null;
        Scope iterScope = null;
        try {
            RunnerState state = services.preflightState();
            while (!state.isTerminal()) {
                if (state.isInsideIterationSpan() && iterSpan == null) {
                    iterSpan = agentOtelContext.startIterationSpan(
                            context.sessionId(), stateContext.iteration(), context.agentRole());
                    @SuppressWarnings("MustBeClosedChecker") // Scope stored in iterScope, closed in finally block
                    Scope scope = iterSpan.makeCurrent();
                    iterScope = scope;
                }

                RunnerState nextState;
                try {
                    nextState = state.execute(stateContext);
                } catch (Exception e) {
                    if (iterSpan != null) {
                        iterSpan.recordException(e);
                    }
                    throw e;
                }

                if (iterSpan != null && !nextState.isInsideIterationSpan()) {
                    iterScope.close();
                    iterSpan.end();
                    iterSpan = null;
                    iterScope = null;
                }

                state = nextState;
            }
        } finally {
            if (iterScope != null) {
                iterScope.close();
            }
            if (iterSpan != null) {
                iterSpan.end();
            }
        }
    }
}
