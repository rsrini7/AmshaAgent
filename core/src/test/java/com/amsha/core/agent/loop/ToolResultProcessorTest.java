package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolResultProcessorTest {

    @Mock
    ChatMemoryWriter writer;

    ToolResultProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ToolResultProcessor(writer);
    }

    // ── buildObservation ───────────────────────────────────────

    @Test
    void buildObservation_WhenNullResponse_ReturnsDefaultMessage() {
        String obs = processor.buildObservation(null);
        assertEquals("Tool invocation returned no response.", obs);
    }

    @Test
    void buildObservation_WhenToolSuccess_ReturnsOutput() {
        ToolResponse res = ToolResponse.success("hello world");
        assertEquals("hello world", processor.buildObservation(res));
    }

    @Test
    void buildObservation_WhenToolFailureWithNoOutput_FormatsMessage() {
        ToolResponse res = ToolResponse.failure(1, "");
        String obs = processor.buildObservation(res);
        assertTrue(obs.contains("No output"));
        assertTrue(obs.contains("exit=1"));
    }

    @Test
    void buildObservation_WhenToolFailureWithOutput_PrependsPrefix() {
        ToolResponse res = ToolResponse.failure(1, "permission denied");
        String obs = processor.buildObservation(res);
        assertEquals("Tool execution failed: permission denied (exit=1)", obs);
    }

    @Test
    void buildObservation_WhenToolFailureAlreadyPrefixed_ReturnsAsIs() {
        ToolResponse res = ToolResponse.failure(1, "Tool execution failed: something went wrong");
        assertEquals("Tool execution failed: something went wrong", processor.buildObservation(res));
    }

    @Test
    void buildObservation_WhenToolPending_ReturnsOutput() {
        ToolResponse res = ToolResponse.pending("awaiting approval", "tok_123");
        assertEquals("awaiting approval", processor.buildObservation(res));
    }

    // ── evaluateCircuitBreaker ──────────────────────────────────

    @Test
    void evaluateCircuitBreaker_WhenSuccessResetsFailureCount() {
        ReasoningStep step = ReasoningStep.pending("", "test_tool", Map.of());
        StepDecision decision = processor.evaluateCircuitBreaker(step, ToolResponse.success("ok"), 2, "sid", List.of());
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(0, decision.consecutiveFailures());
    }

    @Test
    void evaluateCircuitBreaker_WhenFailureUnderThreshold_ReturnsProceedWithFailures() {
        ReasoningStep step = ReasoningStep.pending("", "test_tool", Map.of());
        StepDecision decision =
                processor.evaluateCircuitBreaker(step, ToolResponse.failure(1, "err"), 1, "sid", List.of());
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(2, decision.consecutiveFailures());
    }

    @Test
    void evaluateCircuitBreaker_WhenFailuresReachThreshold_TripsCircuitBreaker() {
        ReasoningStep step = ReasoningStep.pending("", "test_tool", Map.of());
        StepDecision decision =
                processor.evaluateCircuitBreaker(step, ToolResponse.failure(1, "err"), 2, "sid", List.of());
        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.Finished);
    }

    // ── resolveOutcome routing ──────────────────────────────────

    private AgentExecutionContext ctx() {
        return new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "do something",
                AutonomyLevel.FULL,
                null,
                5,
                0,
                null,
                null,
                null,
                null);
    }

    @Test
    void resolveOutcome_WhenNotSpecialTool_FallsBackToCircuitBreaker() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", "generic_tool", Map.of());
        var res = ToolResponse.failure(1, "err");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(ctx, "generic_tool", Map.of(), res, obs, step, List.of(), 0);
        assertEquals(1, decision.consecutiveFailures());
    }

    @Test
    void resolveOutcome_ScheduleTaskSuccess_TriggersTerminal() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.SCHEDULE_TASK.toolName(), Map.of());
        var res = ToolResponse.success("task created");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx, ToolCapability.SCHEDULE_TASK.toolName(), Map.of(), res, obs, step, List.of(), 0);
        assertEquals(DecisionType.TERMINAL, decision.type());
        verify(writer).saveAnswer("sess-1", "✅ task created");
    }

    @Test
    void resolveOutcome_ManageScheduleNonListAction_TriggersTerminal() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.MANAGE_SCHEDULE.toolName(), Map.of());
        var res = ToolResponse.success("deleted");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx,
                ToolCapability.MANAGE_SCHEDULE.toolName(),
                Map.of("action", "delete"),
                res,
                obs,
                step,
                List.of(),
                0);
        assertEquals(DecisionType.TERMINAL, decision.type());
        verify(writer).saveAnswer("sess-1", "✅ deleted");
    }

    @Test
    void resolveOutcome_ManageScheduleListAction_DoesNotTerminateWhenNotListOnlyGoal() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.MANAGE_SCHEDULE.toolName(), Map.of());
        var res = ToolResponse.success("list output");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx, ToolCapability.MANAGE_SCHEDULE.toolName(), Map.of("action", "list"), res, obs, step, List.of(), 0);
        assertEquals(DecisionType.TERMINAL, decision.type());
        verify(writer).saveAnswer("sess-1", "list output");
    }

    @Test
    void resolveOutcome_DelegateSuccessWithoutWarning_TriggersProceed() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of());
        var res = ToolResponse.success("task delegated");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx, ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of(), res, obs, step, List.of(), 0);
        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void resolveOutcome_GithubNotAvailable_TriggersTerminal() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.GITHUB.toolName(), Map.of());
        var res = ToolResponse.failure(1, "GitHub integration is not available");
        var obs = processor.buildObservation(res);

        StepDecision decision =
                processor.resolveOutcome(ctx, ToolCapability.GITHUB.toolName(), Map.of(), res, obs, step, List.of(), 0);
        assertEquals(DecisionType.TERMINAL, decision.type());
    }

    @Test
    void resolveOutcome_BrowserNavigateFailure_TriggersRetry() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.BROWSER_NAVIGATE.toolName(), Map.of());
        var res = ToolResponse.failure(1, "timeout");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx,
                ToolCapability.BROWSER_NAVIGATE.toolName(),
                Map.of("url", "https://example.com"),
                res,
                obs,
                step,
                new ArrayList<>(),
                0);
        assertEquals(DecisionType.CONTINUE, decision.type());
        assertNotNull(decision.transientError());
        assertTrue(decision.transientError().contains(ToolCapability.WEB_SEARCH_SERPER.toolName()));
    }

    @Test
    void resolveOutcome_BrowserNavigateFailureWithoutUrl_FallsBackToCircuitBreaker() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.BROWSER_NAVIGATE.toolName(), Map.of());
        var res = ToolResponse.failure(1, "timeout");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx, ToolCapability.BROWSER_NAVIGATE.toolName(), Map.of(), res, obs, step, List.of(), 0);
        assertEquals(1, decision.consecutiveFailures());
    }
}
