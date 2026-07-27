package com.amsha.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.enterprise.event.Event;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import com.amsha.app.AgentStateObserver;
import com.amsha.app.CliAgentObserver;
import com.amsha.app.ui.dto.AgentEvent;
import com.amsha.core.agent.event.AgentStateChangedEvent;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AgentObserversCoverageTest {

    @Test
    void progressObserverDescribesEveryActionKind() {
        @SuppressWarnings("unchecked")
        Event<AgentEvent> events = mock(Event.class);
        AgentProgressObserver observer = new AgentProgressObserver();
        observer.progressEvent = events;

        observer.recordLlmCall("session", 1, 2);
        observer.onStepComplete(null, "role", step("tool", true, "ok"));
        observer.onStepComplete("session", "role", null);
        for (ReasoningStep step : List.of(
                step("", true, "ok"),
                step(ReasoningStep.FINISH, true, "ok"),
                step(ReasoningStep.PARALLEL_SENTINEL_DUNDER, true, "ok"),
                step(ReasoningStep.OBSERVE, true, "ok"),
                step(ToolCapability.DELEGATE_TO_AGENT.toolName(), true, "ok"),
                step(ToolCapability.SANDBOX_EXEC.toolName(), true, "ok"),
                step(ToolCapability.BROWSER_NAVIGATE.toolName(), true, "ok"),
                step(ToolCapability.GITHUB.toolName(), true, "ok"),
                step(ToolCapability.WEB_SEARCH_BRAVE.toolName(), true, "ok"),
                step(ToolCapability.WEB_SEARCH_SERPER.toolName(), true, "ok"),
                step("custom_tool", false, "ok"),
                step("___", true, "ok"))) {
            observer.onStepComplete("session", "role", step);
        }

        verify(events, times(12)).fire(any(AgentEvent.class));
    }

    @Test
    void metricsObserverRecordsTokensToolsParallelBatchesAndCompletions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsAgentObserver observer = new MetricsAgentObserver();
        observer.registry = registry;

        observer.recordLlmCall("session", 3, 5);
        observer.onStepComplete("session", null, step("custom_tool", true, "ok"));
        observer.onStepComplete("session", "coordinator", step(ReasoningStep.PARALLEL_SENTINEL_DUNDER, true, "ok"));
        observer.onStepComplete("session", "coordinator", step(ReasoningStep.FINISH, true, "ok"));

        assertEquals(3, registry.counter("amsha.agent.tokens", "type", "input").count());
        assertEquals(5, registry.counter("amsha.agent.tokens", "type", "output").count());
        assertEquals(1, registry.counter("amsha.agent.llm.calls").count());
        assertEquals(
                1,
                registry.counter("amsha.agent.parallel.batches", "role", "coordinator")
                        .count());
        assertEquals(
                1,
                registry.counter("amsha.agent.completions", "role", "coordinator")
                        .count());
    }

    @Test
    void cliAndStateObserversCoverEnabledOutputAndCompletion() {
        CliAgentObserver cli = new CliAgentObserver();
        setField(cli, "log", mock(Logger.class));
        cli.recordLlmCall("session", 1, 2);
        cli.onStepComplete("session", "role", step("tool", true, "ignored"));
        String longObservation = "x".repeat(130);
        ScopedValue.where(CliAgentObserver.ENABLED, true).run(() -> {
            cli.recordLlmCall("session", 7, 9);
            cli.onStepComplete("session", "role", step("tool", true, longObservation));
        });

        @SuppressWarnings("unchecked")
        Event<AgentStateChangedEvent> events = mock(Event.class);
        AgentStateObserver state = new AgentStateObserver();
        setField(state, "stateChangedEvent", events);
        state.recordLlmCall("session", 1, 2);
        state.onLoopComplete("session", "user", "role", "goal", new AgentResult.Finished("done", List.of(), 1, 2));
        verify(events).fire(any(AgentStateChangedEvent.class));
    }

    private static ReasoningStep step(String action, Boolean success, String observation) {
        return ReasoningStep.of("thought", action, Map.of(), observation, 1, 2, success);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
