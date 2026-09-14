package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.Instance;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.micrometer.core.instrument.MeterRegistry;

class ParallelToolStepExecutorTest {

    @Test
    void testExecuteEmptyBatchTerminates() {
        ParallelToolStepExecutor executor = new ParallelToolStepExecutor();

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "solve issue",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                1,
                "telegram",
                "plat-1",
                null,
                Map.of());

        ReasoningStep batchStep = ReasoningStep.parallelBatch("think", Map.of(), 0, 0);

        StepDecision decision = executor.execute(context, List.of(), batchStep, 0);
        assertNotNull(decision);
        assertEquals(DecisionType.TERMINAL, decision.type());
        assertInstanceOf(AgentResult.Failed.class, decision.result());
        AgentResult.Failed failed = (AgentResult.Failed) decision.result();
        assertTrue(failed.reason().contains("empty or corrupt"));
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
    }

    @Test
    void testExecuteBlockedToolCallRetriesValidation() throws Exception {
        ToolInvocationGuard mockGuard = Mockito.mock(ToolInvocationGuard.class);
        Mockito.when(mockGuard.evaluate(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Blocked("Tool access blocked"));

        ObjectMapper mapper = new ObjectMapper();
        ParallelToolStepExecutor executor = new ParallelToolStepExecutor(
                mapper,
                mockGuard,
                null,
                Mockito.mock(Instance.class),
                Mockito.mock(GovernanceStrategy.class),
                Mockito.mock(RateLimitTracker.class),
                Mockito.mock(MeterRegistry.class),
                null,
                Mockito.mock(Instance.class),
                null,
                null,
                Optional.empty(),
                60);

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "solve issue",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                1,
                "telegram",
                "plat-1",
                null,
                Map.of());

        List<ParallelToolCall> calls =
                List.of(ParallelToolCall.pending("c1", "blocked_tool", Map.of("k", "v"), "{\"k\":\"v\"}"));
        ReasoningStep batchStep =
                ReasoningStep.parallelBatch("think", Map.of("batch", mapper.writeValueAsString(calls)), 0, 0);

        ArrayList<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = executor.execute(context, history, batchStep, 0);
        assertNotNull(decision);
        assertEquals(DecisionType.CONTINUE, decision.type());
        assertTrue(decision.isRetryValidation());
        assertEquals(1, history.size());
    }

    @Test
    void testRedirectUnauthorizedCallWithDelegationResolver() throws Exception {
        ToolDelegationResolver mockResolver = Mockito.mock(ToolDelegationResolver.class);
        Mockito.when(mockResolver.resolveOwner("coder", "search_tool")).thenReturn(Optional.of("researcher"));

        ObjectMapper mapper = new ObjectMapper();
        ParallelToolStepExecutor executor = new ParallelToolStepExecutor();
        var resolverField = ParallelToolStepExecutor.class.getDeclaredField("delegationResolver");
        resolverField.setAccessible(true);
        resolverField.set(executor, mockResolver);
        var mapperField = ParallelToolStepExecutor.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(executor, mapper);

        AgentExecutionContext context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "goal",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                1,
                "telegram",
                "plat-1",
                null,
                Map.of());

        var redirectMethod = ParallelToolStepExecutor.class.getDeclaredMethod(
                "redirectUnauthorizedCall", AgentExecutionContext.class, ParallelToolCall.class);
        redirectMethod.setAccessible(true);

        ParallelToolCall call = ParallelToolCall.pending("c1", "search_tool", Map.of("q", "query"), "{}");
        ParallelToolCall redirected = (ParallelToolCall) redirectMethod.invoke(executor, context, call);

        assertNotNull(redirected);
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), redirected.toolName());
    }

    // ================= executor selection =================

    /** Wires the CDI {@code Instance<ManagedExecutor>} lookup so {@code executorFor} can be exercised. */
    private static ParallelToolStepExecutor withManagedExecutors(Instance<ManagedExecutor> pool) throws Exception {
        ParallelToolStepExecutor executor = new ParallelToolStepExecutor();
        var field = ParallelToolStepExecutor.class.getDeclaredField("managedExecutors");
        field.setAccessible(true);
        field.set(executor, pool);
        return executor;
    }

    private static ManagedExecutor executorFor(ParallelToolStepExecutor executor, ToolCategory category)
            throws Exception {
        var method = ParallelToolStepExecutor.class.getDeclaredMethod("executorFor", ToolCategory.class);
        method.setAccessible(true);
        return (ManagedExecutor) method.invoke(executor, category);
    }

    @Test
    void testExecutorForPrefersTheCategorySpecificPool() throws Exception {
        ManagedExecutor specific = Mockito.mock(ManagedExecutor.class);

        @SuppressWarnings("unchecked")
        Instance<ManagedExecutor> selected = Mockito.mock(Instance.class);
        Mockito.when(selected.stream()).thenReturn(Stream.of(specific));

        @SuppressWarnings("unchecked")
        Instance<ManagedExecutor> pool = Mockito.mock(Instance.class);
        Mockito.when(pool.select(ArgumentMatchers.<Annotation>any())).thenReturn(selected);

        // Each tool category has its own named pool; the default is only a fallback.
        assertSame(specific, executorFor(withManagedExecutors(pool), ToolCategory.NETWORK));
    }

    @Test
    void testExecutorForFallsBackToTheDefaultPool() throws Exception {
        ManagedExecutor fallback = Mockito.mock(ManagedExecutor.class);

        @SuppressWarnings("unchecked")
        Instance<ManagedExecutor> selected = Mockito.mock(Instance.class);
        Mockito.when(selected.stream()).thenReturn(Stream.empty());
        Mockito.when(selected.get()).thenReturn(fallback);

        @SuppressWarnings("unchecked")
        Instance<ManagedExecutor> pool = Mockito.mock(Instance.class);
        Mockito.when(pool.select(ArgumentMatchers.<Annotation>any())).thenReturn(selected);

        assertSame(fallback, executorFor(withManagedExecutors(pool), ToolCategory.COMPUTE));
    }

    // ================= rewriteDelegatedCalls =================

    private static List<ParallelToolCall> rewriteDelegatedCalls(
            List<ParallelToolCall> calls, List<ParallelToolCall> completed) throws Exception {
        var method = ParallelToolStepExecutor.class.getDeclaredMethod("rewriteDelegatedCalls", List.class, List.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ParallelToolCall> result =
                (List<ParallelToolCall>) method.invoke(new ParallelToolStepExecutor(), calls, completed);
        return result;
    }

    @Test
    void testRewriteDelegatedCallsLeavesCallsWithoutACompletedMatchAlone() throws Exception {
        ParallelToolCall original = ParallelToolCall.pending("c1", "search_tool", Map.of("q", "x"), "{}");

        List<ParallelToolCall> result = rewriteDelegatedCalls(List.of(original), List.of());

        assertEquals(List.of(original), result);
    }

    @Test
    void testRewriteDelegatedCallsRearmsAMatchedDelegationAsPending() throws Exception {
        ParallelToolCall original = ParallelToolCall.pending("c1", "search_tool", Map.of("q", "x"), "{}");
        ParallelToolCall completedDelegate = new ParallelToolCall(
                "c1",
                ToolCapability.DELEGATE_TO_AGENT.toolName(),
                Map.of("agent", "researcher"),
                "{\"agent\":\"researcher\"}",
                "delegated answer",
                true);

        List<ParallelToolCall> result = rewriteDelegatedCalls(List.of(original), List.of(completedDelegate));

        assertEquals(1, result.size());
        ParallelToolCall rewritten = result.get(0);
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), rewritten.toolName());
        // Re-armed for replay: no observation or success flag may survive from the completed run.
        assertNull(rewritten.observation());
        assertFalse(rewritten.success());
    }

    @Test
    void testRewriteDelegatedCallsIgnoresAMatchingCallIdWithADifferentTool() throws Exception {
        ParallelToolCall original = ParallelToolCall.pending("c1", "search_tool", Map.of(), "{}");
        // Same callId, but not a delegation -> it must not be rewritten.
        ParallelToolCall completedOther = new ParallelToolCall("c1", "search_tool", Map.of(), "{}", "done", true);

        List<ParallelToolCall> result = rewriteDelegatedCalls(List.of(original), List.of(completedOther));

        assertSame(original, result.get(0));
    }
}
