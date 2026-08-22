package com.amsha.app.observability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.Locale;

import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.observability.AgentObserver;
import com.amsha.tool.api.ToolCapability;

@ApplicationScoped
public class AgentProgressObserver implements AgentObserver {

    @Inject
    Event<AgentEvent> progressEvent;

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        // No-op: this observer only emits coarse progress updates.
    }

    @Override
    public void onStepComplete(String sessionId, String role, ReasoningStep step) {
        if (sessionId == null || step == null || progressEvent == null) {
            return;
        }

        ProgressSnapshot snapshot = describe(step);
        progressEvent.fire(new AgentEvent(sessionId, "progress", snapshot.phase(), snapshot.detail()));
    }

    private ProgressSnapshot describe(ReasoningStep step) {
        String action = step.action();
        if (action == null || action.isBlank()) {
            return new ProgressSnapshot("Working", "Updating the plan");
        }

        if (ReasoningStep.FINISH.equals(action)) {
            return new ProgressSnapshot("Drafting final answer", "Wrapping up the response");
        }
        if (ReasoningStep.PARALLEL_SENTINEL_DUNDER.equals(action)) {
            return new ProgressSnapshot("Running tools in parallel", "Coordinating multiple checks");
        }
        if (ReasoningStep.OBSERVE.equals(action)) {
            return new ProgressSnapshot("Reviewing results", "Checking the latest output");
        }

        String normalized = action.toLowerCase(Locale.ROOT);
        if (ToolCapability.DELEGATE_TO_AGENT.toolName().equals(normalized)) {
            return new ProgressSnapshot("Handing off work", "Consulting a specialist");
        }
        if (ToolCapability.SANDBOX_EXEC.toolName().equals(normalized)) {
            return new ProgressSnapshot("Running code", "Executing the sandbox");
        }
        if (ToolCapability.BROWSER_NAVIGATE.toolName().equals(normalized)) {
            return new ProgressSnapshot("Reading a page", "Inspecting the current page");
        }
        if (ToolCapability.GITHUB.toolName().equals(normalized)) {
            return new ProgressSnapshot("Checking GitHub", "Reviewing repository data");
        }
        if (ToolCapability.WEB_SEARCH_BRAVE.toolName().equals(normalized)
                || ToolCapability.WEB_SEARCH_SERPER.toolName().equals(normalized)) {
            return new ProgressSnapshot("Searching the web", "Looking for current information");
        }

        String detail = humanize(action);
        if (Boolean.FALSE.equals(step.success())) {
            return new ProgressSnapshot("Working through a tool issue", detail);
        }
        return new ProgressSnapshot("Working", detail);
    }

    private String humanize(String action) {
        String normalized = action.replace('_', ' ').trim();
        if (normalized.isBlank()) {
            return "Processing";
        }
        return Character.toUpperCase(normalized.charAt(0))
                + normalized.substring(1).toLowerCase(Locale.ROOT);
    }

    private record ProgressSnapshot(String phase, String detail) {}
}
