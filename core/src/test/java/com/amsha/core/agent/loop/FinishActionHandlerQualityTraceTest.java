package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.trace.AgentTraceEvent;
import com.amsha.core.agent.trace.StationCode;
import com.amsha.core.agent.trace.TracePublisher;
import com.amsha.core.agent.trace.TraceStage;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the {@code quality_check} trace emitted at the authoritative final-answer guard in
 * {@link FinishActionHandler} (Issue 02, station 8). The status reflects the genuine guard outcome
 * ({@code PASS} / {@code SALVAGED} / {@code FAILURE}); nothing is synthesized when the guard never
 * runs. Publishing must also stay transaction-free and must never change the handler's outcome.
 */
class FinishActionHandlerQualityTraceTest {

    private FinishActionHandler handler;
    private AnswerGuardStrategy qualityGuard;
    private TracePublisher tracePublisher;

    private final ArgumentCaptor<AgentTraceEvent> captor = ArgumentCaptor.forClass(AgentTraceEvent.class);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        qualityGuard = mock(AnswerGuardStrategy.class);
        CodeGenerationPolicy codePolicy = mock(CodeGenerationPolicy.class);
        RateLimitTracker rateLimitTracker = mock(RateLimitTracker.class);
        Instance<AgentObserver> observers = mock(Instance.class);
        CorrectionStrategy correctionStrategy = mock(CorrectionStrategy.class);
        TextPatternUtils textPatternUtils = mock(TextPatternUtils.class);
        RoleRegistry roleRegistry = mock(RoleRegistry.class);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools()).thenReturn(List.of(ToolCapability.WEB_SEARCH_SERPER.toolName()));
        when(roleRegistry.resolvePermittedTools(anyString())).thenReturn(Set.of());

        handler = new FinishActionHandler(
                qualityGuard,
                codePolicy,
                rateLimitTracker,
                observers,
                correctionStrategy,
                new ObjectMapper(),
                textPatternUtils,
                toolRegistry,
                roleRegistry);
        tracePublisher = mock(TracePublisher.class);
        handler.tracePublisher = tracePublisher;

        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(false);
        when(textPatternUtils.looksLikeJson(anyString())).thenReturn(false);
    }

    private static ReasoningStep finishStep(String answer) {
        return ReasoningStep.pending("Action: FINISH\nAnswer: " + answer, ReasoningStep.FINISH, Map.of(), 0, 0);
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "researcher",
                "summarize this",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                Map.of());
    }

    @Test
    void qualityCheckEmitsPassWhenGuardAcceptsAnswer() {
        when(qualityGuard.guard(anyString(), any()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("a clean answer", false, null));

        handler.handle(context(), List.of(), finishStep("a clean answer"), 0);

        verify(tracePublisher, times(1)).publish(captor.capture());
        AgentTraceEvent event = captor.getValue();
        assertEquals(TraceStage.QUALITY_CHECK, event.stage());
        assertEquals(StationCode.STATION_08_QUALITY, event.station());
        assertEquals("PASS", event.status());
        assertNull(event.detail(), "no failure reason when the guard passes");
        assertEquals("s1", event.sessionId());
    }

    @Test
    void qualityCheckEmitsSalvagedWhenGuardStripsEcho() {
        when(qualityGuard.guard(anyString(), any()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("salvaged answer", true, null));

        handler.handle(context(), List.of(), finishStep("salvaged answer"), 0);

        verify(tracePublisher, times(1)).publish(captor.capture());
        AgentTraceEvent event = captor.getValue();
        assertEquals(TraceStage.QUALITY_CHECK, event.stage());
        assertEquals("SALVAGED", event.status());
    }

    @Test
    void qualityCheckEmitsFailureWithReasonWhenGuardRejectsAnswer() {
        when(qualityGuard.guard(anyString(), any()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("", false, "raw_tool_call_leak:search"));

        FinishActionHandler.FinishOutcome outcome = handler.handle(context(), List.of(), finishStep("leaky"), 0);

        verify(tracePublisher, times(1)).publish(captor.capture());
        AgentTraceEvent event = captor.getValue();
        assertEquals(TraceStage.QUALITY_CHECK, event.stage());
        assertEquals("FAILURE", event.status());
        assertEquals("raw_tool_call_leak:search", event.detail());
        // Instrumentation must not alter the handler's deterministic outcome.
        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }

    @Test
    void noQualityCheckIsFabricatedWhenPublisherAbsent() {
        // Null-safe: a manually-constructed harness without an injected publisher must not fail.
        handler.tracePublisher = null;
        when(qualityGuard.guard(anyString(), any()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("fine", false, null));

        FinishActionHandler.FinishOutcome outcome = handler.handle(context(), List.of(), finishStep("fine"), 0);

        assertTrue(
                outcome instanceof FinishActionHandler.FinishOutcome.Completed
                        || outcome instanceof FinishActionHandler.FinishOutcome.Correction,
                "handler outcome must be unaffected by trace publishing");
    }
}
