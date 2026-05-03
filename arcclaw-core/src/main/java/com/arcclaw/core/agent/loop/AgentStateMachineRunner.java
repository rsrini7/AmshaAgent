package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.structured.StructuredOutputException;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Orchestrates the Thought-Plan-Act-Observe loop.
 */
@ApplicationScoped
public class AgentStateMachineRunner {

    private static final Logger log = Logger.getLogger(AgentStateMachineRunner.class);

    private static final int LOG_GOAL_MAX = 80;
    private static final String GOAL_LOG_FMT = "StateMachine starting session=%s goal='%s' maxIterations=%d";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

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
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final StepDecisionRouter stepDecisionRouter;

    @Inject
    public AgentStateMachineRunner(EngineStepHandler engineStepHandler,
                                   ChatMemoryWriter writer,
                                   Instance<AgentObserver> observers,
                                   SkillRegistry skillRegistry,
                                   AgentOtelContext agentOtelContext,
                                   IntentClassificationService intentDetector,
                                   LoopExitPublisher loopExitPublisher,
                                   FinishActionHandler finishActionHandler,
                                   CorrectionStrategy correctionStrategy,
                                   @ConfigProperty(name = "arcclaw.agent.max-ratelimit-retries", defaultValue = "3") int maxRateLimitRetries,
                                   MeterRegistry meterRegistry,
                                   ToolRegistry toolRegistry,
                                   ToolInvoker toolInvoker,
                                   StepDecisionRouter stepDecisionRouter) {
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
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
        this.stepDecisionRouter = stepDecisionRouter;
    }

    /**
     * Performs a single terminal synthesis pass (FINISH) after a tool has been
     * executed and approved during resumption.
     */
    public AgentResult runFinishPass(AgentExecutionContext context,
                                     ReasoningEngine engine,
                                     List<ReasoningStep> history) {
        log.infof("StateMachine: performing terminal finish pass for session %s", context.sessionId());
        context.setForceFinishRetry(true);
        
        int offset = context.getCheckpointRestoreOffset();
        ReasoningStep lastToolStep = history.stream()
                .skip(offset)
                .filter(s -> ToolCapability.SANDBOX_EXEC.toolName().equals(s.action())
                        && s.observation() != null
                        && !s.observation().isBlank())
                .reduce((_, second) -> second)
                .orElse(null);

        boolean currentTurnHasSandboxAttempt = history.stream()
                .skip(offset)
                .anyMatch(s -> ToolCapability.SANDBOX_EXEC.toolName().equals(s.action()));

        if (lastToolStep != null && currentTurnHasSandboxAttempt) {
            history.add(ReasoningStep.systemMessage(
                    "The sandbox_exec tool returned the following output. Report it EXACTLY:\n" +
                    "OUTPUT: " + lastToolStep.observation() + "\n" +
                    "Do NOT paraphrase, recalculate, or invent values. Use the exact value above."));
        }

        try {
            ReasoningStep step = engine.nextStep(context, context.agentRole(), context.goal(), history);
            FinishActionHandler.FinishOutcome outcome = finishActionHandler.handle(context, history, step, 0);

            if (outcome instanceof FinishActionHandler.FinishOutcome.Completed c) {
                if (context.sessionId() != null && c.answer() != null && !c.answer().isBlank()) {
                    writer.saveAnswer(context.sessionId(), c.answer());
                }
                return c.result();
            }
            
            log.warnf("StateMachine: terminal pass did not result in FINISH for session %s; retrying with guidance hint.", context.sessionId());
            int historySizeBeforeHint = history.size();
            history.add(ReasoningStep.systemMessage("You have already executed the tool. Summarize the result and provide your final answer using the FINISH action now."));
            
            try {
                ReasoningStep retryStep = engine.nextStep(context, context.agentRole(), context.goal(), history);
                FinishActionHandler.FinishOutcome retryOutcome = finishActionHandler.handle(context, history, retryStep, 0);

                if (retryOutcome instanceof FinishActionHandler.FinishOutcome.Completed rc) {
                    if (context.sessionId() != null && rc.answer() != null && !rc.answer().isBlank()) {
                        writer.saveAnswer(context.sessionId(), rc.answer());
                    }
                    return rc.result();
                }
            } finally {
                if (history.size() > historySizeBeforeHint) {
                    history.subList(historySizeBeforeHint, history.size()).clear();
                }
            }

            log.errorf("StateMachine: terminal retry failed for session %s; falling back to standard loop.", context.sessionId());
            return run(context, engine, history);
        } finally {
            context.setForceFinishRetry(false);
        }
    }

    public AgentResult run(AgentExecutionContext context,
                           ReasoningEngine engine,
                           List<ReasoningStep> resumedHistory) {
        context.setCheckpointRestoreOffset(resumedHistory.size());
        List<ReasoningStep> history = new ArrayList<>(resumedHistory);

        int iteration = context.startingIteration();
        int llmSchemaErrorCount = 0;
        int consecutiveFailures = 0;
        int rateLimitRetryCount = 0;
        String transientError = null;

        String goal = context.goal() != null ? context.goal() : "unknown";
        String goalHash = AgentErrorMessageUtil.hashGoal(goal);

        if (log.isInfoEnabled()) {
            String preview = goal.length() > LOG_GOAL_MAX ? goal.substring(0, LOG_GOAL_MAX) : goal;
            log.infof(GOAL_LOG_FMT, context.sessionId(), preview, context.maxIterations());
        }

        loopExitPublisher.publishStart(context);
        writer.saveGoal(context.sessionId(), goal);

        AgentResult finalResult = null;

        try {
            runBrowserPreflight(context, history);

            while (iteration < context.maxIterations() || (iteration == context.maxIterations() && context.isForceFinishRetry())) {
                iteration++;
                IterationResult iterRes = processSingleIteration(context, engine, history, iteration, llmSchemaErrorCount, consecutiveFailures, rateLimitRetryCount, transientError);

                if (iterRes.isTerminal()) {
                    finalResult = iterRes.result();
                    return finalResult;
                }

                if (iterRes.rateLimitRetryCount() > rateLimitRetryCount) {
                    iteration--;
                }

                transientError = iterRes.transientError();
                llmSchemaErrorCount = iterRes.llmSchemaErrorCount();
                consecutiveFailures = iterRes.consecutiveFailures();
                rateLimitRetryCount = iterRes.rateLimitRetryCount();
            }

            finalResult = AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history));
            return finalResult;

        } catch (BudgetExceededException e) {
            finalResult = AgentResult.failed(e.getMessage(), AgentResult.FailureCategory.BUDGET_EXCEEDED, List.copyOf(history));
            return finalResult;
        } catch (Exception e) {
            log.errorf("StateMachine terminal failure for session %s: %s", context.sessionId(), e.getMessage());
            finalResult = AgentResult.failed(e.getMessage(), AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history));
            return finalResult;
        } finally {
            AgentResult res = finalResult;
            meterRegistry.summary("arcclaw.agent.iterations.distribution", "role", context.agentRole()).record(iteration - context.startingIteration());
            loopExitPublisher.publish(context, res);
            observers.forEach(o -> o.onLoopComplete(context.sessionId(), context.userId(), context.agentRole(), goalHash, res));
        }
    }

    private void runBrowserPreflight(AgentExecutionContext context, List<ReasoningStep> history) {
        if (history.isEmpty()) {
            buildBrowserPreflightStep(context).ifPresent(preflightStep -> {
                try {
                    ToolResponse response = WorkspaceContext.run(
                            context.sessionId(), context.userId(), context.chatId(),
                            context.autonomyLevel(), context.agentRole(), context.channelType(),
                            () -> toolInvoker.invoke(preflightStep.action(), preflightStep.actionInput(), context.sessionId()));

                    ReasoningStep completed = preflightStep.withObservation(
                            response.success() ? response.output() : "Browser navigation failed: " + response.output(),
                            response.success());
                    history.add(completed);
                } catch (Exception e) {
                    log.warnf("[BrowserPreflight] Failed for session %s: %s", context.sessionId(), e.getMessage());
                }
            });
        }
    }

    private record IterationResult(
            AgentResult result,
            String transientError,
            int llmSchemaErrorCount,
            int consecutiveFailures,
            int rateLimitRetryCount,
            boolean isTerminal
    ) {
        public static IterationResult terminal(AgentResult result) {
            return new IterationResult(result, null, 0, 0, 0, true);
        }
        public static IterationResult proceed(String transientError, int llmSchemaErrorCount, int consecutiveFailures, int rateLimitRetryCount) {
            return new IterationResult(null, transientError, llmSchemaErrorCount, consecutiveFailures, rateLimitRetryCount, false);
        }
    }

    private IterationResult processSingleIteration(
            AgentExecutionContext context, ReasoningEngine engine, List<ReasoningStep> history,
            int iteration, int llmSchemaErrorCount, int consecutiveFailures, int rateLimitRetryCount, String transientError) {
        
        Span iterSpan = agentOtelContext.startIterationSpan(context.sessionId(), iteration, context.agentRole());
        try (Scope scope = iterSpan.makeCurrent()) {
            if (history.isEmpty() && intentDetector.isWriteIntent(context.goal())) {
                transientError = "The user wants to store data. Call ingest_document immediately.";
            }

            StepDecision engineDecision;
            try {
                engineDecision = engineStepHandler.processEngineStep(context, engine, history, transientError, llmSchemaErrorCount, iteration);

                if (engineDecision.isRateLimitHit()) {
                    if (rateLimitRetryCount + 1 > maxRateLimitRetries) {
                        return IterationResult.terminal(AgentResult.failed("Repeated rate limits.", AgentResult.FailureCategory.SYSTEM_ERROR, List.copyOf(history)));
                    }
                    return IterationResult.proceed(transientError, llmSchemaErrorCount, consecutiveFailures, rateLimitRetryCount + 1);
                }
            } catch (StructuredOutputException soe) {
                if (!correctionStrategy.withinBudget(llmSchemaErrorCount)) {
                    return IterationResult.terminal(AgentResult.loopExceeded(context.maxIterations(), List.copyOf(history)));
                }
                return IterationResult.proceed(correctionStrategy.buildCorrectionHint(llmSchemaErrorCount + 1, soe.getMessage()), llmSchemaErrorCount + 1, consecutiveFailures, rateLimitRetryCount);
            }

            if (engineDecision.type() == DecisionType.TERMINAL) {
                return IterationResult.terminal(engineDecision.result());
            }
            if (engineDecision.type() == DecisionType.CONTINUE) {
                return IterationResult.proceed(engineDecision.transientError(), engineDecision.llmSchemaErrorCount(), consecutiveFailures, rateLimitRetryCount);
            }

            ReasoningStep step = engineDecision.step();
            var outcome = stepDecisionRouter.route(context, history, step, llmSchemaErrorCount, consecutiveFailures, skillRegistry.currentSnapshot());

            if (outcome.isTerminal()) {
                return IterationResult.terminal(outcome.result());
            }
            if (outcome.retryEngine()) {
                return IterationResult.proceed(outcome.transientError(), outcome.llmSchemaErrorCount(), consecutiveFailures, rateLimitRetryCount);
            }

            return IterationResult.proceed(null, llmSchemaErrorCount, outcome.consecutiveFailures(), rateLimitRetryCount);
        } catch (Exception e) {
            iterSpan.recordException(e);
            throw e;
        } finally {
            iterSpan.end();
        }
    }

    private Optional<ReasoningStep> buildBrowserPreflightStep(AgentExecutionContext context) {
        if (!AgentRoleEnum.RESEARCHER.toScopedName().equalsIgnoreCase(context.agentRole())) return Optional.empty();
        var toolOpt = toolRegistry.findTool(ToolCapability.BROWSER_NAVIGATE.toolName());
        if (toolOpt.isEmpty() || !toolOpt.map(AgentTool::isEnabled).orElse(false)) return Optional.empty();

        String goal = context.goal();
        if (goal == null) return Optional.empty();
        Matcher m = URL_PATTERN.matcher(goal);
        if (!m.find()) return Optional.empty();

        String url = m.group();
        return Optional.of(ReasoningStep.fastPath(
                "Pre-flight: URL detected in goal, navigating directly.",
                ToolCapability.BROWSER_NAVIGATE.toolName(),
                Map.of("url", url, "action", "extract_text")));
    }
}
