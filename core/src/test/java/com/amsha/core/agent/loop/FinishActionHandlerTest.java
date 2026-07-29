package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("java:S1192")
class FinishActionHandlerTest {

    private FinishActionHandler handler;
    private AnswerGuardStrategy qualityGuard;
    private CodeGenerationPolicy codePolicy;
    private RateLimitTracker rateLimitTracker;
    private Instance<AgentObserver> observers;
    private CorrectionStrategy correctionStrategy;
    private TextPatternUtils textPatternUtils;
    private RoleRegistry roleRegistry;
    private AnswerQualityShadowEvaluationService answerQualityShadowEvaluation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        qualityGuard = mock(AnswerGuardStrategy.class);
        codePolicy = mock(CodeGenerationPolicy.class);
        rateLimitTracker = mock(RateLimitTracker.class);
        observers = mock(Instance.class);
        correctionStrategy = mock(CorrectionStrategy.class);
        textPatternUtils = mock(TextPatternUtils.class);
        roleRegistry = mock(RoleRegistry.class);
        answerQualityShadowEvaluation = mock(AnswerQualityShadowEvaluationService.class);

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.listTools())
                .thenReturn(List.of(
                        ToolCapability.SANDBOX_EXEC.toolName(),
                        ToolCapability.WEB_SEARCH_SERPER.toolName(),
                        ToolCapability.RECALL_MEMORY_SEARCH.toolName()));
        when(roleRegistry.resolvePermittedTools(anyString())).thenAnswer(invocation -> {
            String r = invocation.getArgument(0);
            if ("coordinator".equals(r)) {
                return Set.of(ToolCapability.SCHEDULE_TASK.toolName(), ToolCapability.RECALL_MEMORY_SEARCH.toolName());
            } else if ("coder".equals(r)) {
                return Set.of(ToolCapability.SANDBOX_EXEC.toolName());
            }
            return Set.of();
        });

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
        handler.answerQualityShadowEvaluation = answerQualityShadowEvaluation;

        // Default mock behaviors
        when(qualityGuard.isRawToolCallLeak(anyString())).thenReturn(false);
        when(qualityGuard.guard(anyString(), any()))
                .thenAnswer(invocation ->
                        new AnswerGuardStrategy.AnswerGuardResult(invocation.getArgument(0), false, null));
        when(textPatternUtils.looksLikeJson(anyString())).thenReturn(false);
    }

    @Test
    void testHandleStripsFinishPrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending("FINISH: Hello world", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void acceptedAnswerIsObservedWithoutChangingFinishOrRetryingAcrossToolBoundary() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.agentRole()).thenReturn("researcher");
        when(ctx.goal()).thenReturn("summarize the result");
        ReasoningStep tool =
                ReasoningStep.pending("tool", "search", Map.of(), 0, 0).withObservation("private tool result", true);
        ReasoningStep finish =
                ReasoningStep.pending("FINISH: Here is the accepted summary.", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(tool), finish, 0);

        assertEquals("Here is the accepted summary.", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
        ArgumentCaptor<AnswerQualityShadowContext> captor = ArgumentCaptor.forClass(AnswerQualityShadowContext.class);
        verify(answerQualityShadowEvaluation).observeAccepted(captor.capture());
        assertTrue(captor.getValue().toolInvokedInTurn());
        assertEquals("Here is the accepted summary.", captor.getValue().answer());
    }

    @Test
    void interceptedExecutionFinish_completesWithoutCorrection() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(qualityGuard.guard(anyString(), any()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("", true, "would normally fail"));
        ReasoningStep step = ReasoningStep.pending(
                "intercepted",
                ReasoningStep.FINISH,
                Map.of(
                        ReasoningStep.ACTION_INPUT_ANSWER_KEY,
                        "```bash\necho should-not-run\n```",
                        ReasoningStep.ACTION_INPUT_INTERCEPTED_EXECUTION_KEY,
                        true),
                0,
                0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Completed);
        assertEquals(
                "```bash\necho should-not-run\n```", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsActionFinishPrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step =
                ReasoningStep.pending("Action: FINISH\nAnswer: Hello world", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Hello world", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsFinalAnswerPrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step =
                ReasoningStep.pending("Final Answer: The result is 42", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("The result is 42", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsResponsePrefix() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step =
                ReasoningStep.pending("Response: Processed successfully", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertEquals("Processed successfully", ((FinishActionHandler.FinishOutcome.Completed) outcome).answer());
    }

    @Test
    void testHandleStripsMultipleConsecutivePrefixes() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        ReasoningStep step = ReasoningStep.pending(
                "Action: FINISH\nAnswer: Final Answer: Hello world", ReasoningStep.FINISH, Map.of(), 0, 0);

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
        ReasoningStep step =
                ReasoningStep.pending("finish: hello\naction: finish", ReasoningStep.FINISH, Map.of(), 0, 0);

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

        ReasoningStep step = ReasoningStep.pending(
                "thought", ReasoningStep.FINISH, Map.of("answer", "Task scheduled successfully. ID: fake-uuid"), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
        String hint = ((FinishActionHandler.FinishOutcome.Correction) outcome).correctionHint();
        assertTrue(hint.contains(
                "STOP. You claimed 'Task scheduled successfully' but you did NOT call the schedule_task tool"));
    }

    @Test
    void testHandleBlocksContractedHallucinatedScheduling() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.agentRole()).thenReturn("coordinator");

        ReasoningStep step = ReasoningStep.pending(
                "thought",
                ReasoningStep.FINISH,
                Map.of("answer", "I've scheduled the recurring task for you. It will run every 3 minutes."),
                0,
                0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }

    @Test
    void testHandleBlocksHallucinatedSchedulingWhenParallelBatchScheduleFailed() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.agentRole()).thenReturn("coordinator");

        String batchJson = """
                [{"callId":"1","toolName":ToolCapability.SCHEDULE_TASK.toolName(),"params":{},"paramsJson":"{}","observation":"TOOL_FAILED: CHANNEL_TYPE not bound","success":false}]
                """;
        ReasoningStep parallel = ReasoningStep.parallelBatch("batch", Map.of("batch", batchJson), 0, 0)
                .withObservation("schedule_task→FAIL", false);
        ReasoningStep finish = ReasoningStep.pending(
                "thought",
                ReasoningStep.FINISH,
                Map.of("answer", "Task scheduled successfully. It will run shortly."),
                0,
                0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(parallel), finish, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
    }

    @Test
    void testHandleRejectsJsonSalvage() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");

        // Mock a tool call leak that the model repeats
        String leakedJson = "{\"tool\":\"github\",\"parameters\":{}}";
        when(qualityGuard.isRawToolCallLeak(leakedJson)).thenReturn(true);
        when(qualityGuard.stripRawToolJson(leakedJson)).thenReturn(leakedJson);
        when(textPatternUtils.looksLikeJson(leakedJson)).thenReturn(true);

        // Ensure correctionStrategy says we are NOT within budget to trigger the final salvage/fail path
        when(correctionStrategy.withinBudget(anyInt())).thenReturn(false);

        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, Map.of("answer", leakedJson), 0, 0);

        // Turn 2: llmSchemaErrorCount = 1 (already leaked once)
        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 1);

        assertTrue(
                outcome instanceof FinishActionHandler.FinishOutcome.SalvageFailed,
                "Should fail salvage if the 'clean' thought is still JSON");
    }

    @Test
    void testHandleBlocksHallucinatedSchedulingWithCustomPhrases() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.agentRole()).thenReturn("coordinator");

        ReasoningStep step = ReasoningStep.pending(
                "thought",
                ReasoningStep.FINISH,
                Map.of("answer", "I have scheduled the task to run in 2 minutes."),
                0,
                0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
        String hint = ((FinishActionHandler.FinishOutcome.Correction) outcome).correctionHint();
        assertTrue(hint.contains(
                "STOP. You claimed to have scheduled/set a reminder but you did NOT call the schedule_task tool"));
    }

    @Test
    void testHandleBlocksSchedulingGoalWithoutToolCall() {
        AgentExecutionContext ctx = mock(AgentExecutionContext.class);
        when(ctx.sessionId()).thenReturn("s1");
        when(ctx.agentRole()).thenReturn("coordinator");
        when(ctx.goal()).thenReturn("schedule python script to run after 2 mins");

        ReasoningStep step = ReasoningStep.pending(
                "thought", ReasoningStep.FINISH, Map.of("answer", "I will do that for you."), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Correction);
        String hint = ((FinishActionHandler.FinishOutcome.Correction) outcome).correctionHint();
        assertTrue(hint.contains(
                "STOP. The user requested a future/scheduled action, but you did NOT call the schedule_task tool"));
    }
}
