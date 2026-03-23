package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.reasoning.LangChainReasoningEngine;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.observability.AgentObserver;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.reflex.PiiScanReflex;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.security.DestructiveVocabularyFilter;
import com.arcclaw.core.skill.SkillRegistry;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.tool.DynamicToolLookup;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.tools.ToolResponse;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import io.opentelemetry.api.trace.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentStateMachineRunnerTest {

    private AgentStateMachineRunner runner;
    private AgentStepProcessor stepProcessor;
    private LangChainReasoningEngine engine;
    private ToolRegistry toolRegistry;
    private StatefulAgentStore agentStore;
    private GovernanceLayer governanceLayer;
    private ToolInvocationGuard guard;
    private ToolInvoker invoker;
    private ChatMemoryWriter writer;
    private SkillRegistry skillRegistry;
    private PiiScanReflex piiScanReflex;
    private AgentOtelContext agentOtelContext;

    @SuppressWarnings("unchecked")
    private Instance<DynamicToolLookup> dynamicToolLookup = mock(Instance.class);

    @SuppressWarnings("unchecked")
    Instance<AgentObserver> observers = mock(Instance.class);

    DestructiveVocabularyFilter destructiveFilter = mock(DestructiveVocabularyFilter.class);

    @BeforeEach
    void setUp() {
        engine           = mock(LangChainReasoningEngine.class);
        toolRegistry     = mock(ToolRegistry.class);
        agentStore       = mock(StatefulAgentStore.class);
        governanceLayer  = mock(GovernanceLayer.class);
        guard            = mock(ToolInvocationGuard.class);
        invoker          = mock(ToolInvoker.class);
        writer           = mock(ChatMemoryWriter.class);
        skillRegistry    = mock(SkillRegistry.class);
        piiScanReflex    = mock(PiiScanReflex.class);
        agentOtelContext = mock(AgentOtelContext.class);

        stepProcessor = new AgentStepProcessor();
        stepProcessor.toolRegistry    = toolRegistry;
        stepProcessor.agentStore      = agentStore;
        stepProcessor.governanceLayer = governanceLayer;
        stepProcessor.observers       = observers;
        stepProcessor.guard           = guard;
        stepProcessor.invoker         = invoker;
        stepProcessor.writer          = writer;
        stepProcessor.dynamicToolLookup = dynamicToolLookup; // ← THE FIX

        runner = new AgentStateMachineRunner();
        runner.stepProcessor    = stepProcessor;
        runner.writer           = writer;
        runner.observers        = observers;
        runner.skillRegistry    = skillRegistry;
        runner.piiScanReflex    = piiScanReflex;
        runner.agentOtelContext = agentOtelContext;

        when(observers.iterator()).thenReturn(Collections.emptyIterator());
        when(skillRegistry.currentSnapshot()).thenReturn(Collections.emptyList());

        Span invalidSpan = Span.getInvalid();
        when(agentOtelContext.startIterationSpan(anyString(), anyInt(), anyString()))
                .thenReturn(invalidSpan);
        when(agentOtelContext.startToolSpan(anyString(), anyString(), anyString()))
                .thenReturn(invalidSpan);

        when(dynamicToolLookup.isUnsatisfied()).thenReturn(true);

        when(destructiveFilter.isDestructive(anyString())).thenReturn(false);
        when(destructiveFilter.filter(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentExecutionContext createCtx() {
        return new AgentExecutionContext(
                "session-1", "user-1", "chat-1", "coordinator",
                "goal", AutonomyLevel.FULL, 5);
    }

    @Test
    void testFinishOnFirstStep() {
        ReasoningStep finishStep = ReasoningStep.pending(
                "done", ReasoningStep.FINISH, Map.of("answer", "Hello"), 0, 0);
        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(finishStep);

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("Hello", ((AgentResult.Finished) result).answer());
        verify(engine, times(1)).nextStep(any(), any(), any(), any());
    }

    @Test
    void testLoopExceededAfterMaxIterations() {
        ReasoningStep toolStep = ReasoningStep.pending("thought", "tool", Map.of(), 0, 0);
        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(toolStep);
        when(toolRegistry.findTool(anyString())).thenReturn(Optional.of(mock(AgentTool.class)));
        when(guard.evaluate(anyString(), anyString(), anyString(), anyMap(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(invoker.invoke(anyString(), anyMap(), anyString()))
                .thenReturn(new ToolResponse(0, "output", true));

        AgentExecutionContext ctx = new AgentExecutionContext(
                "s1", "u1", "c1", "coordinator", "goal", AutonomyLevel.FULL, 2);
        AgentResult result = runner.run(ctx, engine, List.of());

        assertInstanceOf(AgentResult.LoopExceeded.class, result);
        assertEquals(2, ((AgentResult.LoopExceeded) result).maxIterations());
    }

    @Test
    void testHallucinatedToolIntercepted() {
        ReasoningStep hallucinatedStep = ReasoningStep.pending(
                "thought", "fake-tool", Map.of(), 0, 0);
        ReasoningStep finishStep = ReasoningStep.pending(
                "done", ReasoningStep.FINISH, Map.of("answer", "fixed"), 0, 0);

        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(hallucinatedStep)
                .thenReturn(finishStep);
        when(toolRegistry.findTool("fake-tool")).thenReturn(Optional.empty());
        when(toolRegistry.listTools()).thenReturn(List.of("valid-tool"));

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("fixed", ((AgentResult.Finished) result).answer());
        verify(engine, times(2)).nextStep(any(), any(), any(), any());
    }

    @Test
    void testBudgetExceededAborts() throws Exception {
        ReasoningStep toolStep = ReasoningStep.pending("thought", "tool", Map.of(), 100, 100);
        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(toolStep);
        doThrow(new BudgetExceededException("Budget blown"))
                .when(governanceLayer).recordLlmCall(anyString(), anyInt(), anyInt());

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Failed.class, result);
        assertEquals(AgentResult.FailureCategory.BUDGET_EXCEEDED,
                ((AgentResult.Failed) result).category());
        assertTrue(((AgentResult.Failed) result).reason().contains("Budget blown"));
    }

    @Test
    void testAwaitingApprovalPausesLoop() {
        WorkspaceContext.run("s1", "u1", "c1", AutonomyLevel.SUPERVISED, "coordinator", () -> {
            ReasoningStep toolStep = ReasoningStep.pending(
                    "thought", "protected-tool", Map.of(), 0, 0);
            when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                    .thenReturn(toolStep);
            when(toolRegistry.findTool("protected-tool"))
                    .thenReturn(Optional.of(mock(AgentTool.class)));
            when(guard.evaluate(anyString(), anyString(), anyString(), anyMap(),
                    anyList(), anyString(), anyList()))
                    .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(
                            "protected-tool", "{}"));
            when(agentStore.load(anyString(), anyString()))
                    .thenReturn(Optional.of(new AgentCheckpoint()));

            AgentExecutionContext ctx = new AgentExecutionContext(
                    "s1", "u1", "c1", "coordinator", "goal", AutonomyLevel.SUPERVISED, 5);
            AgentResult result = runner.run(ctx, engine, List.of());

            assertInstanceOf(AgentResult.AwaitingApproval.class, result);
            assertEquals("protected-tool",
                    ((AgentResult.AwaitingApproval) result).toolName());
            verify(agentStore).save(any(AgentCheckpoint.class));
        });
    }

    @Test
    void testCircuitBreakerTripsAfter2Failures() {
        ReasoningStep toolStep1 = ReasoningStep.pending(
                "thought1", "broken-tool", Map.of("arg", 1), 0, 0);
        ReasoningStep toolStep2 = ReasoningStep.pending(
                "thought2", "broken-tool", Map.of("arg", 2), 0, 0);
        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(toolStep1)
                .thenReturn(toolStep2);
        when(toolRegistry.findTool("broken-tool")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(guard.evaluate(anyString(), anyString(), anyString(), anyMap(),
                anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(invoker.invoke(anyString(), anyMap(), anyString()))
                .thenReturn(new ToolResponse(1, "error", false));

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("❌"));
        verify(invoker, times(2)).invoke(anyString(), anyMap(), anyString());
    }

    @Test
    void testSchemaErrorSelfHealsOnce() {
        InvalidRequestException ex = new InvalidRequestException("bad schema");
        ReasoningStep finishStep = ReasoningStep.pending(
                "done", ReasoningStep.FINISH, Map.of("answer", "healed"), 0, 0);

        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(ex)
                .thenReturn(finishStep);

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("healed", ((AgentResult.Finished) result).answer());
        verify(engine, times(2)).nextStep(any(), any(), any(), any());
    }

    @Test
    void testSchedulingToolFastPathExits() {
        ReasoningStep scheduleStep = ReasoningStep.pending(
                "planning", "scheduletask", Map.of("task", "clean"), 0, 0);
        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenReturn(scheduleStep);
        when(toolRegistry.findTool("scheduletask")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(guard.evaluate(anyString(), anyString(), anyString(), anyMap(),
                anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
        when(invoker.invoke(anyString(), anyMap(), anyString()))
                .thenReturn(new ToolResponse(0, "Scheduled cleaning", true));

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertTrue(((AgentResult.Finished) result).answer().contains("Scheduled cleaning"));
        verify(engine, times(1)).nextStep(any(), any(), any(), any());
        verify(writer).saveAnswer(anyString(), anyString());
    }

    @Test
    void testRateLimitReturnsRateLimitCategory() {
        HttpException ex = new HttpException(429, "Rate limit reached");
        when(engine.nextStep(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(ex);

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Failed.class, result);
        assertEquals(AgentResult.FailureCategory.RATE_LIMIT,
                ((AgentResult.Failed) result).category());
        assertTrue(((AgentResult.Failed) result).reason().contains("⏳"));
    }

    @Test
    void testReactKeywordDoesNotPersistToHistory() {
        ReasoningStep reactStep = ReasoningStep.pending(
                "I will observe the result", "OBSERVE", Map.of(), 0, 0);
        ReasoningStep finishStep = ReasoningStep.pending(
                "done", ReasoningStep.FINISH, Map.of("answer", "fixed"), 0, 0);

        when(engine.nextStep(any(), any(), any(), anyList()))
                .thenReturn(reactStep)
                .thenReturn(finishStep);
        when(toolRegistry.findTool("OBSERVE")).thenReturn(Optional.empty());
        when(toolRegistry.listTools()).thenReturn(List.of("github", "memory_search"));

        AgentResult result = runner.run(createCtx(), engine, List.of());

        assertInstanceOf(AgentResult.Finished.class, result);
        assertEquals("fixed", ((AgentResult.Finished) result).answer());
        assertFalse(
                result.steps().stream().anyMatch(s -> "OBSERVE".equals(s.action())),
                "OBSERVE ReAct keyword must NOT be persisted to reasoning history"
        );
    }
}
