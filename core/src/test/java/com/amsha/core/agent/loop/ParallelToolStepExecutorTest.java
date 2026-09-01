package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
import com.fasterxml.jackson.databind.ObjectMapper;

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
}
