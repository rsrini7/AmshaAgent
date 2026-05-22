package com.arcclaw.core.agent.loop;

import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.skill.SkillRegistry;

public class RunnerServices {
    private final EngineStepHandler engineStepHandler;
    private final StepDecisionRouter stepDecisionRouter;
    private final SkillRegistry skillRegistry;
    private final CorrectionStrategy correctionStrategy;
    private final BrowserPreflightHandler browserPreflightHandler;
    private final IntentClassificationService intentDetector;
    private final int maxRateLimitRetries;

    private final PreflightState preflightState;
    private final ThinkingState thinkingState;
    private final CompletedState completedState;
    private final FailedState failedState;
    private final AwaitingApprovalState awaitingApprovalState;

    public RunnerServices(EngineStepHandler engineStepHandler,
                          StepDecisionRouter stepDecisionRouter,
                          SkillRegistry skillRegistry,
                          CorrectionStrategy correctionStrategy,
                          BrowserPreflightHandler browserPreflightHandler,
                          IntentClassificationService intentDetector,
                          int maxRateLimitRetries) {
        this.engineStepHandler = engineStepHandler;
        this.stepDecisionRouter = stepDecisionRouter;
        this.skillRegistry = skillRegistry;
        this.correctionStrategy = correctionStrategy;
        this.browserPreflightHandler = browserPreflightHandler;
        this.intentDetector = intentDetector;
        this.maxRateLimitRetries = maxRateLimitRetries;

        this.preflightState = new PreflightState(this);
        this.thinkingState = new ThinkingState(this);
        this.completedState = new CompletedState();
        this.failedState = new FailedState();
        this.awaitingApprovalState = new AwaitingApprovalState();
    }

    public EngineStepHandler engineStepHandler() {
        return engineStepHandler;
    }

    public StepDecisionRouter stepDecisionRouter() {
        return stepDecisionRouter;
    }

    public SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    public CorrectionStrategy correctionStrategy() {
        return correctionStrategy;
    }

    public BrowserPreflightHandler browserPreflightHandler() {
        return browserPreflightHandler;
    }

    public IntentClassificationService intentDetector() {
        return intentDetector;
    }

    public int maxRateLimitRetries() {
        return maxRateLimitRetries;
    }

    public PreflightState preflightState() {
        return preflightState;
    }

    public ThinkingState thinkingState() {
        return thinkingState;
    }

    public CompletedState completedState() {
        return completedState;
    }

    public FailedState failedState() {
        return failedState;
    }

    public AwaitingApprovalState awaitingApprovalState() {
        return awaitingApprovalState;
    }

    public RunnerState terminalStateForResult(AgentResult result) {
        if (result instanceof AgentResult.Finished) {
            return completedState;
        } else if (result instanceof AgentResult.AwaitingApproval) {
            return awaitingApprovalState;
        } else {
            return failedState;
        }
    }
}
