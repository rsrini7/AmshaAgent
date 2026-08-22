package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.classifier.ClassifierResult;
import com.amsha.core.classifier.ClassifierResult.NoOpinionReason;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.routing.ModelRouter;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;
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
    private AnswerQualityDispatcher answerQualityDispatcher;

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
        answerQualityDispatcher = mock(AnswerQualityDispatcher.class);

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
        handler.answerQualityDispatcher = answerQualityDispatcher;
        when(answerQualityDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.NoOpinion<>(NoOpinionReason.MISSING_MODEL));

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
        verify(answerQualityShadowEvaluation).observeAccepted(anyString(), captor.capture());
        assertTrue(captor.getValue().toolInvokedInTurn());
        assertEquals("Here is the accepted summary.", captor.getValue().answer());
    }

    @Test
    void activeAnswerQualityConsumesOnlyOnePreToolRetry() {
        AgentExecutionContext context = context("researcher", "summarize this");
        when(answerQualityDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.Opinion<>(AnswerQualityAction.REQUEST_RETRY, 0.91f, "test-quality"));
        ReasoningStep finish = ReasoningStep.pending(
                "FINISH: This is a complete candidate answer.", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome first = handler.handle(context, List.of(), finish, 0);
        FinishActionHandler.FinishOutcome second = handler.handle(context, List.of(), finish, 0);

        assertInstanceOf(FinishActionHandler.FinishOutcome.Correction.class, first);
        assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, second);
        assertEquals(1, context.getAnswerQualityRetryCount());
    }

    @Test
    void activeAnswerQualityCannotRetryAfterAnyToolBoundary() {
        AgentExecutionContext context = context("researcher", "summarize this");
        when(answerQualityDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.Opinion<>(AnswerQualityAction.REQUEST_RETRY, 0.91f, "test-quality"));
        ReasoningStep tool =
                ReasoningStep.pending("tool", "search", Map.of(), 0, 0).withObservation("result", true);
        ReasoningStep finish = ReasoningStep.pending(
                "FINISH: This is the final answer after a tool.", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(context, List.of(tool), finish, 0);

        assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, outcome);
        assertEquals(0, context.getAnswerQualityRetryCount());
    }

    @Test
    void pendingToolCallStepWithoutObservationIsToolBoundary() {
        AgentExecutionContext context = context("researcher", "summarize this");
        when(answerQualityDispatcher.evaluate(any()))
                .thenReturn(new ClassifierResult.Opinion<>(AnswerQualityAction.REQUEST_RETRY, 0.91f, "test-quality"));
        ReasoningStep pendingTool = ReasoningStep.pending("tool", "search", Map.of(), 0, 0);
        ReasoningStep finish = ReasoningStep.pending(
                "FINISH: Candidate after a pending tool call.", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(context, List.of(pendingTool), finish, 0);

        assertInstanceOf(FinishActionHandler.FinishOutcome.Completed.class, outcome);
        assertEquals(0, context.getAnswerQualityRetryCount());
        verify(answerQualityDispatcher).evaluate(any());
    }

    @Test
    void deterministicAnswerGuardFailureBypassesActiveInterceptors() {
        AgentExecutionContext context = context("researcher", "summarize this");
        when(qualityGuard.guard(anyString(), any()))
                .thenReturn(new AnswerGuardStrategy.AnswerGuardResult("", false, "deterministic failure"));
        ReasoningStep finish = ReasoningStep.pending(
                "FINISH: This answer is rejected deterministically.", ReasoningStep.FINISH, Map.of(), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(context, List.of(), finish, 0);

        assertInstanceOf(FinishActionHandler.FinishOutcome.Correction.class, outcome);
        verify(answerQualityDispatcher, never()).evaluate(any());
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

    @Test
    void testHandle_fencedJsonWithCodeForGreeting_neverInjectsToolsOrApprovals() {
        AgentExecutionContext ctx = context("coordinator", "Hi");

        String fencedJson =
                "```json\n{\n\"status\": \"done\",\n\"code\": \"public class PrimeFinder {\\n public static void main(String[] args) {}\\n}\"\n}\n```";
        when(textPatternUtils.looksLikeJson(anyString())).thenReturn(true);

        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, Map.of("answer", fencedJson), 0, 0);

        // Turn 1: Should return Correction (plain text instruction), NEVER InjectToolCall
        FinishActionHandler.FinishOutcome outcome1 = handler.handle(ctx, List.of(), step, 0);
        assertTrue(
                outcome1 instanceof FinishActionHandler.FinishOutcome.Correction,
                "Greeting with JSON should trigger Correction, not InjectToolCall");
        assertFalse(
                outcome1 instanceof FinishActionHandler.FinishOutcome.InjectToolCall,
                "Greeting must NEVER inject tool calls or garuda");

        // Turn 2 (forceFinishRetry): Model still returns JSON -> Must safely fall back to plain-text greeting
        ctx.setForceFinishRetry(true);
        FinishActionHandler.FinishOutcome outcome2 = handler.handle(ctx, List.of(), step, 1);
        assertTrue(
                outcome2 instanceof FinishActionHandler.FinishOutcome.Completed,
                "Force-finish retry on greeting with JSON should complete with safe fallback");
        FinishActionHandler.FinishOutcome.Completed completed = (FinishActionHandler.FinishOutcome.Completed) outcome2;
        assertFalse(completed.answer().contains("```"), "Final answer must not contain code blocks");
        assertFalse(completed.answer().contains("\"status\":"), "Final answer must not contain JSON fields");
        assertTrue(completed.answer().contains("Hello"), "Final answer should be a safe greeting");
    }

    @Test
    void testHandle_fencedJavaCodeForGreeting_neverInjectsToolsOrApprovals() {
        AgentExecutionContext ctx = context("coordinator", "Hi");

        String javaCode = "```java\npublic class PrimeFinder {\n    public static void main(String[] args) {}\n}\n```";
        when(textPatternUtils.looksLikeJson(anyString())).thenReturn(false);
        when(codePolicy.isGeneratedCodeBlock(anyString(), anyString())).thenReturn(true);
        when(codePolicy.canGenerateCode("coordinator")).thenReturn(false);

        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, Map.of("answer", javaCode), 0, 0);

        // Turn 1: Should return Correction, NEVER InjectToolCall for garuda
        FinishActionHandler.FinishOutcome outcome1 = handler.handle(ctx, List.of(), step, 0);
        assertTrue(
                outcome1 instanceof FinishActionHandler.FinishOutcome.Correction,
                "Greeting with code should trigger Correction, not InjectToolCall");
        assertFalse(
                outcome1 instanceof FinishActionHandler.FinishOutcome.InjectToolCall,
                "Greeting must NEVER inject garuda delegation or create approvals");

        // Turn 2 (forceFinishRetry): Safe greeting fallback
        ctx.setForceFinishRetry(true);
        FinishActionHandler.FinishOutcome outcome2 = handler.handle(ctx, List.of(), step, 1);
        assertTrue(
                outcome2 instanceof FinishActionHandler.FinishOutcome.Completed,
                "Force-finish retry on greeting with code should complete with safe fallback");
        FinishActionHandler.FinishOutcome.Completed completed = (FinishActionHandler.FinishOutcome.Completed) outcome2;
        assertFalse(completed.answer().contains("```"), "Final answer must not contain code blocks");
        assertTrue(completed.answer().contains("Hello"), "Final answer should be a safe greeting");
    }

    @Test
    void testHandle_legitimateCodeRequest_coordinatorDelegatesToCoder() {
        String codeGoal = "Write a prime number finder in java and execute it";
        AgentExecutionContext ctx = context("coordinator", codeGoal);

        String javaCode = "```java\npublic class PrimeFinder {\n    public static void main(String[] args) {}\n}\n```";
        when(textPatternUtils.looksLikeJson(anyString())).thenReturn(false);
        when(codePolicy.isGeneratedCodeBlock(anyString(), anyString())).thenReturn(true);
        when(codePolicy.canGenerateCode("coordinator")).thenReturn(false);

        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, Map.of("answer", javaCode), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);
        assertTrue(
                outcome instanceof FinishActionHandler.FinishOutcome.InjectToolCall,
                "Legitimate code request from coordinator MUST delegate to coder");
        FinishActionHandler.FinishOutcome.InjectToolCall injected =
                (FinishActionHandler.FinishOutcome.InjectToolCall) outcome;
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), injected.toolName());
        assertEquals("brahma", injected.arguments().get("agent"));
    }

    @Test
    void testHandle_interceptedExecutionInGreeting_neverExposesCode() {
        AgentExecutionContext ctx = context("coordinator", "Hi");

        String javaCode = "```java\npublic class PrimeFinder {\n    public static void main(String[] args) {}\n}\n```";
        ReasoningStep step = ReasoningStep.pending(
                "thought",
                ReasoningStep.FINISH,
                Map.of(
                        ReasoningStep.ACTION_INPUT_ANSWER_KEY,
                        javaCode,
                        ReasoningStep.ACTION_INPUT_INTERCEPTED_EXECUTION_KEY,
                        true),
                0,
                0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 0);
        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Completed);
        FinishActionHandler.FinishOutcome.Completed completed = (FinishActionHandler.FinishOutcome.Completed) outcome;
        assertFalse(completed.answer().contains("```"), "Intercepted execution in greeting must not leak code");
        assertTrue(completed.answer().contains("Hello"), "Should complete with safe greeting fallback");
    }

    @Test
    void testHandle_nonGreetingConversationalFallback_doesNotSayHello() {
        String conversationalGoal = "Tell me a joke";
        AgentExecutionContext ctx = context("coordinator", conversationalGoal);
        ctx.setForceFinishRetry(true);

        String rawJson = "{\"joke\": \"why did the chicken cross the road?\"}";
        when(textPatternUtils.looksLikeJson(anyString())).thenReturn(true);

        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, Map.of("answer", rawJson), 0, 0);

        FinishActionHandler.FinishOutcome outcome = handler.handle(ctx, List.of(), step, 1);
        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Completed);
        FinishActionHandler.FinishOutcome.Completed completed = (FinishActionHandler.FinishOutcome.Completed) outcome;
        assertFalse(completed.answer().contains("Hello!"), "Non-greeting conversational fallback must not say Hello");
        assertFalse(completed.answer().contains("{"), "Must not leak raw JSON");
    }

    private static AgentExecutionContext context(String role, String goal) {
        return new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                role,
                goal,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                Map.of());
    }

    private static ReasoningStep memorySearchStep() {
        return new ReasoningStep.ToolCallStep(
                "t",
                "recall_memory_search",
                Map.of("query", "user name"),
                "Name: Alice",
                0,
                0,
                true,
                null,
                null,
                null,
                false,
                false);
    }

    private static ReasoningStep disclaimerFinishStep() {
        return new ReasoningStep.FinishStep(
                "t",
                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "I do not have any information about your name"),
                0,
                0,
                false);
    }

    @Test
    void memorySynthesisFirstBlockReturnsTemplateCorrection() {
        AgentExecutionContext ctx = context("coordinator", "who am i");

        FinishActionHandler.FinishOutcome outcome =
                handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0);

        assertTrue(
                outcome instanceof FinishActionHandler.FinishOutcome.Correction, "first block must correct, not exit");
        String hint = ((FinishActionHandler.FinishOutcome.Correction) outcome).correctionHint();
        assertTrue(hint.contains("Based on my memory of you"), "hint must be a fill-in template");
        assertTrue(hint.contains("Name: Alice"), "hint must quote the memory result verbatim");
        assertTrue(ctx.isForceFinishRetry());
    }

    @Test
    void memorySynthesisSecondBlockExitsWithVerbatimMemoryWhenNoEscalationTier() {
        AgentExecutionContext ctx = context("coordinator", "who am i");

        handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 1: corrected
        FinishActionHandler.FinishOutcome outcome =
                handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 2

        assertTrue(outcome instanceof FinishActionHandler.FinishOutcome.Completed, "no tier → deterministic exit");
        String answer = ((FinishActionHandler.FinishOutcome.Completed) outcome).answer();
        assertTrue(answer.contains("Here's what my memory of you contains"));
        assertTrue(answer.contains("Name: Alice"), "fallback must quote stored facts verbatim");
        assertFalse(answer.contains("I do not have any information"), "false disclaimer must not survive");
    }

    @Test
    void memorySynthesisSecondBlockEscalatesWhenDistinctTierExists() {
        handler.modelRouter = mock(ModelRouter.class);
        when(handler.modelRouter.hasDistinctEscalationTier(anyString())).thenReturn(true);
        AgentExecutionContext ctx = context("coordinator", "who am i");

        handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 1: corrected
        FinishActionHandler.FinishOutcome outcome =
                handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 2

        assertTrue(
                outcome instanceof FinishActionHandler.FinishOutcome.Correction, "tier exists → one escalated retry");
        assertTrue(ctx.consumeReasoningEscalation(), "escalation flag must be armed for the next call");
        assertFalse(ctx.consumeReasoningEscalation(), "flag is one-shot");
    }

    @Test
    void memorySynthesisThirdBlock_exitsWithVerbatimMemoryEvenWhenTierExists() {
        handler.modelRouter = mock(ModelRouter.class);
        when(handler.modelRouter.hasDistinctEscalationTier(anyString())).thenReturn(true);
        AgentExecutionContext ctx = context("coordinator", "who am i");

        handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 1: corrected
        handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 2: escalated retry
        FinishActionHandler.FinishOutcome outcome =
                handler.handle(ctx, List.of(memorySearchStep()), disclaimerFinishStep(), 0); // attempt 3: exhausted

        assertTrue(
                outcome instanceof FinishActionHandler.FinishOutcome.Completed, "ladder must terminate at attempt 3");
        String answer = ((FinishActionHandler.FinishOutcome.Completed) outcome).answer();
        assertTrue(answer.contains("Name: Alice"), "fallback must quote stored facts verbatim");
    }
}
