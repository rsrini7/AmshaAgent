package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.message.UserMessages;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

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
    void resolveOutcome_SuccessfulArtifactOpen_OnGenericTool_TerminatesImmediately() {
        var ctx = ctx();
        var step = ReasoningStep.pending("", "custom_render", Map.of("path", "output/particle-art.html"));
        var res = ToolResponse.success("Opened browser preview: file:///private/tmp/particle_art.html");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx, "custom_render", Map.of("path", "output/particle-art.html"), res, obs, step, List.of(), 0);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.Finished);
        assertEquals(
                "The requested artifact was generated and opened successfully.",
                ((AgentResult.Finished) decision.result()).answer());
        verify(writer).saveAnswer("sess-1", "The requested artifact was generated and opened successfully.");
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
    void resolveOutcome_DelegatePausedSuccessShape_TriggersDeterministicTerminal() {
        // F1 regression: the delegate tool reports a paused delegation as SUCCESS.
        // The terminal guard must still fire deterministically (not rely on the LLM FINISHing).
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of());
        String pausedMsg = UserMessages.delegationPaused("core:researcher", "parallel|batch");
        assertTrue(pausedMsg.contains(UserMessages.DELEGATION_PAUSED_MARKER));
        assertTrue(pausedMsg.contains("/approve"));
        assertTrue(pausedMsg.contains("/deny"));
        assertTrue(pausedMsg.contains("separate approval"));
        assertTrue(pausedMsg.contains("`/approve` again"));
        var res = ToolResponse.success(pausedMsg);

        StepDecision decision = processor.resolveOutcome(
                ctx, ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of(), res, pausedMsg, step, List.of(), 0);
        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.AwaitingApproval);
        String userSafe = "⏳ " + UserMessages.userSafePortion(pausedMsg);
        assertTrue(userSafe.contains("/approve"));
        assertTrue(userSafe.contains("/deny"));
        verify(writer).saveAnswer("sess-1", userSafe);
        // Directive tail must never reach the chat channel.
        assertFalse(userSafe.contains("CRITICAL DIRECTIVE"));
        assertFalse(userSafe.contains("FINISH"));
    }

    @Test
    void resolveOutcome_DelegatePausedLegacyFailureShape_TriggersTerminal() {
        // Legacy failure(409) shape must keep terminating as well.
        var ctx = ctx();
        var step = ReasoningStep.pending("", ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of());
        var res = ToolResponse.failure(409, "Delegation to 'core:researcher' paused for human approval (session: s)");
        var obs = processor.buildObservation(res);

        StepDecision decision = processor.resolveOutcome(
                ctx, ToolCapability.DELEGATE_TO_AGENT.toolName(), Map.of(), res, obs, step, List.of(), 0);
        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.AwaitingApproval);
        verify(writer).saveAnswer("sess-1", "⏳ " + UserMessages.userSafePortion(obs));
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

    @Test
    void resolveOutcome_SerperWebSearchWithOpenSourcePhrase_DoesNotTerminateAsArtifactCompletion() {
        // Regression: web search results containing phrases like "open source" used to match the
        // artifact-completion fast path (which checks for the "open " hint), causing the delegated
        // research loop to terminate with a generic artifact-success message instead of synthesizing
        // the actual research result. serper_web_search is NOT an artifact-capable tool, so the
        // fast path must be skipped and execution should fall through to the circuit-breaker path.
        var ctx = ctx();
        String obsText = "1. Project Foo is an open source initiative backed by the community.\n"
                + "2. Open source licenses compared.\n"
                + "Latest LTS version is 42.";
        var step = ReasoningStep.pending("", ToolCapability.WEB_SEARCH_SERPER.toolName(), Map.of("query", "foo"));
        var res = ToolResponse.success(obsText);

        StepDecision decision = processor.resolveOutcome(
                ctx,
                ToolCapability.WEB_SEARCH_SERPER.toolName(),
                Map.of("query", "foo"),
                res,
                obsText,
                step,
                List.of(),
                0);

        assertNotEquals(DecisionType.TERMINAL, decision.type());
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(0, decision.consecutiveFailures());
        assertNull(decision.result());
    }
}
