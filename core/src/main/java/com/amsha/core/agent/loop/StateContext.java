package com.amsha.core.agent.loop;

import java.util.ArrayList;
import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;

public class StateContext {
    private final AgentExecutionContext executionContext;
    private final ReasoningEngine engine;
    private final List<ReasoningStep> history;
    private int iteration;
    private int llmSchemaErrorCount;
    private int consecutiveFailures;
    private int rateLimitRetryCount;
    private String transientError;
    private AgentResult finalResult;
    private boolean browserPreflightChecked;
    private int noProgressCount;

    public StateContext(AgentExecutionContext executionContext, ReasoningEngine engine, List<ReasoningStep> history) {
        this.executionContext = executionContext;
        this.engine = engine;
        this.history = new ArrayList<>(history);
        this.iteration = executionContext.startingIteration();
        this.llmSchemaErrorCount = 0;
        this.consecutiveFailures = 0;
        this.rateLimitRetryCount = 0;
        this.transientError = null;
        this.finalResult = null;
        this.browserPreflightChecked = false;
        this.noProgressCount = 0;
    }

    public AgentExecutionContext executionContext() {
        return executionContext;
    }

    public ReasoningEngine engine() {
        return engine;
    }

    public List<ReasoningStep> history() {
        return history;
    }

    public int iteration() {
        return iteration;
    }

    public void incrementIteration() {
        this.iteration++;
    }

    public void decrementIteration() {
        this.iteration--;
    }

    public int llmSchemaErrorCount() {
        return llmSchemaErrorCount;
    }

    public void setLlmSchemaErrorCount(int llmSchemaErrorCount) {
        this.llmSchemaErrorCount = llmSchemaErrorCount;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public int rateLimitRetryCount() {
        return rateLimitRetryCount;
    }

    public void incrementRateLimitRetryCount() {
        this.rateLimitRetryCount++;
    }

    public String transientError() {
        return transientError;
    }

    public void setTransientError(String transientError) {
        this.transientError = transientError;
    }

    public AgentResult finalResult() {
        return finalResult;
    }

    public void setFinalResult(AgentResult finalResult) {
        this.finalResult = finalResult;
    }

    public boolean isBrowserPreflightChecked() {
        return browserPreflightChecked;
    }

    public void setBrowserPreflightChecked(boolean browserPreflightChecked) {
        this.browserPreflightChecked = browserPreflightChecked;
    }

    public int noProgressCount() {
        return noProgressCount;
    }

    public int recordNoProgress() {
        return ++noProgressCount;
    }

    public void resetNoProgress() {
        noProgressCount = 0;
    }
}
