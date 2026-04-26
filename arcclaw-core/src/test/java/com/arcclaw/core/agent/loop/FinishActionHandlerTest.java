package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.inject.Instance;

import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinishActionHandlerTest {

    private FinishActionHandler handler;
    private AnswerGuardStrategy qualityGuard;
    private CodeGenerationPolicy codePolicy;
    private RateLimitTracker rateLimitTracker;
    private Instance<AgentObserver> observers;
    private CorrectionStrategy correctionStrategy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        qualityGuard = mock(AnswerGuardStrategy.class);
        codePolicy = mock(CodeGenerationPolicy.class);
        rateLimitTracker = mock(RateLimitTracker.class);
        observers = mock(Instance.class);
        correctionStrategy = mock(CorrectionStrategy.class);

        handler = new FinishActionHandler(qualityGuard, codePolicy, rateLimitTracker, observers, correctionStrategy);

        // Default mock behaviors
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(false);
        when(qualityGuard.guard(anyString(), any())).thenAnswer(invocation -> 
            new AnswerGuardStrategy.AnswerGuardResult(invocation.getArgument(0), false, null));
    }

    @Test
    void testHandleStripsFinishPrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("FINISH: Hello world", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsActionFinishPrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Action: FINISH\nAnswer: Hello world", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsFinalAnswerPrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Final Answer: The result is 42", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("The result is 42", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsResponsePrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Response: Processed successfully", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Processed successfully", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsMultipleConsecutivePrefixes() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Action: FINISH\nAnswer: Final Answer: Hello world", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsFinishSuffix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Hello world\nFINISH", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsBoldFinishSuffix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Hello world\n**FINISH**", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsFinishSuffixWithPunctuation() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("Hello world\nFINISH:", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleCaseInsensitivity() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("finish: hello\naction: finish", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("hello", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandlePreservesFinishInMiddle() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("I will finish the task now.", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("I will finish the task now.", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleBlocksHallucinatedScheduling() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.agentRole()).thenReturn("coordinator");
        
        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, 
                Map.of("answer", "Task scheduled successfully. ID: fake-uuid"), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
        String hint = ((FinishActionHandler.FinishOutcome.Correction) outcome).correctionHint();
        assertTrue(hint.contains("STOP. You claimed 'Task scheduled successfully' but you did NOT call the schedule_task tool"));
    }

    @Test
    void testHandleRejectsJsonSalvage() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        
        // Mock a tool call leak that the model repeats
        String leakedJson = "{\"tool\":\"github\",\"parameters\":{}}";
        when(qualityGuard.isRawToolCallLeak(leakedJson)).thenReturn(true);
        when(qualityGuard.stripRawToolJson(leakedJson)).thenReturn(leakedJson);
        
        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, 
                Map.of("answer", leakedJson), 0, 0);

        // Turn 2: llmSchemaErrorCount = 1 (already leaked once)
        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 1);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.SalvageFailed, 
                "Should fail salvage if the 'clean' thought is still JSON");
    }
}
