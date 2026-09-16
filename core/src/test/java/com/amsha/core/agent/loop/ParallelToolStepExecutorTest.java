package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.enterprise.inject.Instance;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.governance.RateLimitTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.message.UserMessages;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.tools.ToolCategoryMapper;
import com.amsha.core.util.MdcKeys;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolCategory;
import com.amsha.tool.api.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ParallelToolStepExecutorTest {

    private static final ParallelToolCall CALL_1 =
            ParallelToolCall.pending("c1", "web_search", Map.of("q", "a"), "{\"q\":\"a\"}");

    private static final ParallelToolCall CALL_2 =
            ParallelToolCall.pending("c2", "web_fetch", Map.of("url", "b"), "{\"url\":\"b\"}");

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

    // ================= test harness =================

    /**
     * An executor that runs every submitted task on the calling thread, so
     * {@code CompletableFuture.supplyAsync(supplier, executor)} completes synchronously and the whole
     * batch can be driven without a real thread pool.
     */
    private static ManagedExecutor inlineExecutor() {
        ManagedExecutor inline = Mockito.mock(ManagedExecutor.class);
        Mockito.doAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                })
                .when(inline)
                .execute(ArgumentMatchers.any(Runnable.class));
        return inline;
    }

    /** An executor that accepts each task and never runs it, so every future stays incomplete. */
    private static ManagedExecutor stalledExecutor() {
        return Mockito.mock(ManagedExecutor.class);
    }

    @SuppressWarnings("unchecked")
    private static Instance<ManagedExecutor> poolOf(ManagedExecutor executor) {
        Instance<ManagedExecutor> selected = Mockito.mock(Instance.class);
        // A fresh stream per lookup: executorFor() is called once per tool call, and a stubbed
        // thenReturn(...) would hand every call the same already-consumed Stream.
        Mockito.when(selected.stream()).thenAnswer(invocation -> Stream.of(executor));

        Instance<ManagedExecutor> pool = Mockito.mock(Instance.class);
        Mockito.when(pool.select(ArgumentMatchers.<Annotation>any())).thenReturn(selected);
        return pool;
    }

    private static AgentExecutionContext context(String sessionId, AutonomyLevel autonomy) {
        return new AgentExecutionContext(
                sessionId,
                "u1",
                "c1",
                "coder",
                "solve issue",
                autonomy,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "telegram",
                "plat-1",
                null,
                Map.of());
    }

    private static ReasoningStep batchStep(ObjectMapper mapper, List<ParallelToolCall> calls) throws Exception {
        return ReasoningStep.parallelBatch("think", Map.of("batch", mapper.writeValueAsString(calls)), 0, 0);
    }

    private static void allowEveryCall(Fixture fixture) {
        Mockito.when(fixture.guard.evaluate(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
    }

    /**
     * Stubs {@code persistBatchWithPreExecuted}. It takes six arguments - the two trailing
     * {@code String} channel coordinates are easy to drop and the compiler reports the mismatch as
     * an argument-count error on the varargs, not on the missing parameter.
     */
    private static void stubPersistWithPreExecuted(Fixture fixture, AgentResult canned) {
        Mockito.when(fixture.checkpointManager.persistBatchWithPreExecuted(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(canned);
    }

    private static void verifyPersistWithPreExecuted(Fixture fixture) {
        Mockito.verify(fixture.checkpointManager)
                .persistBatchWithPreExecuted(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
    }

    /** Wires the twelve collaborators of {@link ParallelToolStepExecutor} for one scenario. */
    private static final class Fixture {
        final ObjectMapper objectMapper = new ObjectMapper();
        final ToolInvocationGuard guard = Mockito.mock(ToolInvocationGuard.class);
        final ApprovalCheckpointManager checkpointManager = Mockito.mock(ApprovalCheckpointManager.class);
        final GovernanceStrategy governance = Mockito.mock(GovernanceStrategy.class);
        final RateLimitTracker rateLimitTracker = Mockito.mock(RateLimitTracker.class);
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final SequentialToolExecutor sequential = Mockito.mock(SequentialToolExecutor.class);
        final ToolCategoryMapper categoryMapper = Mockito.mock(ToolCategoryMapper.class);
        final ToolDelegationResolver delegationResolver = Mockito.mock(ToolDelegationResolver.class);
        final AgentObserver observer = Mockito.mock(AgentObserver.class);
        final List<AgentObserver> observerList = new ArrayList<>(List.of(observer));
        Optional<List<String>> preExecutableTools = Optional.empty();
        long toolTimeoutSeconds = 60;

        Fixture() {
            Mockito.when(categoryMapper.categoryFor(ArgumentMatchers.any())).thenReturn(ToolCategory.INTERNAL);
        }

        /**
         * An {@code Instance<AgentObserver>} whose {@code forEach} genuinely fans out to
         * {@link #observerList}. A bare mock would swallow every callback, which would make the
         * batch-lifecycle assertions below vacuous.
         */
        @SuppressWarnings("unchecked")
        Instance<AgentObserver> observers() {
            Instance<AgentObserver> instance = Mockito.mock(Instance.class);
            Mockito.doAnswer(invocation -> {
                        Consumer<? super AgentObserver> consumer = invocation.getArgument(0);
                        observerList.forEach(consumer);
                        return null;
                    })
                    .when(instance)
                    .forEach(ArgumentMatchers.any());
            return instance;
        }

        ParallelToolStepExecutor build(ManagedExecutor executor) {
            return new ParallelToolStepExecutor(
                    objectMapper,
                    guard,
                    checkpointManager,
                    observers(),
                    governance,
                    rateLimitTracker,
                    meterRegistry,
                    sequential,
                    poolOf(executor),
                    categoryMapper,
                    delegationResolver,
                    preExecutableTools,
                    toolTimeoutSeconds);
        }
    }

    // ================= execute(): batch lifecycle =================

    @Test
    void execute_successfulBatchRunsEveryCallAndProceeds() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);

        // Capture the batch MDC keys from inside a tool, i.e. while the batch is still running.
        List<String> mdcDuringExecution = new ArrayList<>();
        Mockito.when(fixture.sequential.execute(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    mdcDuringExecution.add(
                            MDC.get(MdcKeys.PARALLEL_BATCH_SIZE) + "/" + MDC.get(MdcKeys.PARALLEL_TOOLS));
                    return ((ParallelToolCall) invocation.getArgument(1)).withResult("ok", true);
                });

        List<ParallelToolCall> calls = List.of(CALL_1, CALL_2);
        ArrayList<ReasoningStep> history = new ArrayList<>();

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(context("sess-1", AutonomyLevel.FULL), history, batchStep(fixture.objectMapper, calls), 0);

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals("[web_search → OK], [web_fetch → OK]", decision.step().observation());
        assertEquals(1, history.size());
        Mockito.verify(fixture.sequential, Mockito.times(2))
                .execute(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());

        assertEquals(List.of("2/web_search,web_fetch", "2/web_search,web_fetch"), mdcDuringExecution);
        // ...and they must not leak past the step.
        assertNull(MDC.get(MdcKeys.PARALLEL_BATCH_SIZE));
        assertNull(MDC.get(MdcKeys.PARALLEL_TOOLS));

        Mockito.verify(fixture.observer).onBatchStart("sess-1", "coder", 2);
        Mockito.verify(fixture.observer).onBatchComplete("sess-1", "coder");
        Mockito.verify(fixture.observer).onStepComplete("sess-1", "coder", decision.step());
    }

    @Test
    void execute_budgetExceededAbortsBeforeAnyToolRuns() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        Mockito.doThrow(new BudgetExceededException("session exceeded its USD budget"))
                .when(fixture.governance)
                .assertWithinBudget("sess-1");

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1)),
                        0);

        assertEquals(DecisionType.TERMINAL, decision.type());
        AgentResult.Failed failed = (AgentResult.Failed) decision.result();
        assertEquals(AgentResult.FailureCategory.BUDGET_EXCEEDED, failed.category());
        assertTrue(failed.reason().contains("USD budget"));
        Mockito.verifyNoInteractions(fixture.sequential);
    }

    @Test
    void execute_rateLimitedProviderDoesNotStopTheBatch() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        Mockito.when(fixture.rateLimitTracker.isAnyBackingOff()).thenReturn(true);
        Mockito.when(fixture.sequential.execute(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> ((ParallelToolCall) invocation.getArgument(1)).withResult("ok", true));

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1)),
                        0);

        // A backing-off LLM provider is a warning only; the tools still run.
        assertEquals(DecisionType.PROCEED, decision.type());
        Mockito.verify(fixture.sequential)
                .execute(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void execute_haltedSessionCancelsPendingFuturesBeforeTheyRun() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);

        WorkspaceManager.haltSession("sess-halted");
        try {
            StepDecision decision = fixture.build(stalledExecutor())
                    .execute(
                            context("sess-halted", AutonomyLevel.FULL),
                            new ArrayList<>(),
                            batchStep(fixture.objectMapper, List.of(CALL_1, CALL_2)),
                            0);

            assertEquals(DecisionType.PROCEED, decision.type());
            Mockito.verifyNoInteractions(fixture.sequential);
        } finally {
            WorkspaceManager.clearHalted("sess-halted");
        }
    }

    // ================= execute(): failure arms of runAllowedCallsAsync =================

    @Test
    void execute_toolTimeoutIsRecordedAsAFailedCallAndAMetric() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        // A zero timeout makes an incomplete future fail immediately - no sleeping required.
        fixture.toolTimeoutSeconds = 0;

        StepDecision decision = fixture.build(stalledExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1)),
                        0);

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals("[web_search → FAIL]", decision.step().observation());
        assertEquals(
                1.0,
                fixture.meterRegistry
                        .counter("amsha.agent.tool.timeout", "tool", "web_search")
                        .count(),
                0.0);

        ArgumentCaptor<ReasoningStep> stepCaptor = ArgumentCaptor.forClass(ReasoningStep.class);
        Mockito.verify(fixture.observer, Mockito.times(2))
                .onStepComplete(Mockito.eq("sess-1"), Mockito.eq("coder"), stepCaptor.capture());
        assertTrue(stepCaptor.getAllValues().getFirst().observation().contains("Tool timed out after 0s."));
    }

    @Test
    void execute_asyncExecutionFailureIsReportedAsAFailedCall() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        Mockito.when(fixture.sequential.execute(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("tool blew up"));

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1)),
                        0);

        // The supplier's exception surfaces as an ExecutionException; the batch survives it.
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals("[web_search → FAIL]", decision.step().observation());
    }

    @Test
    void execute_interruptWhileAwaitingAToolEndsTheBatch() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        fixture.toolTimeoutSeconds = 30;

        Thread main = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            // Interrupt only once the caller is parked inside future.get(timeout). An interrupt
            // delivered any earlier is indistinguishable from a halt and would take the
            // cancel-and-break branch instead of the InterruptedException arm under test.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                Thread.State state = main.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    break;
                }
                Thread.onSpinWait();
            }
            main.interrupt();
        });
        interrupter.setDaemon(true);

        try {
            interrupter.start();
            StepDecision decision = fixture.build(stalledExecutor())
                    .execute(
                            context("sess-1", AutonomyLevel.FULL),
                            new ArrayList<>(),
                            batchStep(fixture.objectMapper, List.of(CALL_1)),
                            0);

            assertEquals(DecisionType.TERMINAL, decision.type());
            assertEquals("Parallel tool execution interrupted.", ((AgentResult.Failed) decision.result()).reason());
            assertTrue(Thread.interrupted(), "the executor must restore the interrupt flag for its caller");
        } finally {
            interrupter.interrupt();
            Thread.interrupted();
        }
    }

    @Test
    void execute_unexpectedRuntimeFailurePropagatesToTheCaller() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        Mockito.when(fixture.categoryMapper.categoryFor(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("category lookup exploded"));

        // Only the "Interrupted" sentinel is converted into a result; anything else is rethrown.
        assertThrows(
                IllegalStateException.class,
                () -> fixture.build(inlineExecutor())
                        .execute(
                                context("sess-1", AutonomyLevel.FULL),
                                new ArrayList<>(),
                                batchStep(fixture.objectMapper, List.of(CALL_1)),
                                0));
    }

    // ================= execute(): HITL batch handling =================

    @Test
    void execute_hitlPauseWithoutPreExecutableToolsPersistsTheWholeBatch() throws Exception {
        Fixture fixture = new Fixture();
        ApprovalFrame frame = new ApprovalFrame("host_exec", Map.of("cmd", "ls"), RiskLevel.HIGH);
        Mockito.when(fixture.guard.evaluate(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.PauseForApproval(frame));
        Mockito.when(fixture.checkpointManager.persistBatch(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(AgentResult.awaitingApproval(frame, "sess-1", List.of()));

        // SUPERVISED autonomy means nothing is pre-executable, so the whole batch pauses.
        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.SUPERVISED),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1, CALL_2)),
                        0);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertInstanceOf(AgentResult.AwaitingApproval.class, decision.result());
        Mockito.verify(fixture.checkpointManager)
                .persistBatch(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());
        Mockito.verifyNoInteractions(fixture.sequential);
    }

    @Test
    void execute_hitlPausePreExecutesEveryCallWhenAllBecomeSafe() throws Exception {
        Fixture fixture = new Fixture();
        ApprovalFrame frame = new ApprovalFrame("host_exec", Map.of(), RiskLevel.MEDIUM);
        // The guard pauses the batch on its first look, but re-evaluating each call for the
        // pre-execution split (FULL autonomy) finds them all safe.
        Mockito.when(fixture.guard.evaluate(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(
                        new ToolInvocationGuard.GuardDecision.PauseForApproval(frame),
                        new ToolInvocationGuard.GuardDecision.Allow(),
                        new ToolInvocationGuard.GuardDecision.Allow());
        Mockito.when(fixture.sequential.invoke(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> ToolResponse.success("pre-" + invocation.getArgument(0)));

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1, CALL_2)),
                        0);

        // Nothing is left pending, so the batch completes instead of pausing.
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals("[web_search → OK], [web_fetch → OK]", decision.step().observation());
        Mockito.verify(fixture.sequential, Mockito.times(2))
                .invoke(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verifyNoInteractions(fixture.checkpointManager);
    }

    @Test
    void execute_hitlPausePreExecutesSafeCallsAndPersistsTheRest() throws Exception {
        Fixture fixture = new Fixture();
        ApprovalFrame frame = new ApprovalFrame("host_exec", Map.of(), RiskLevel.MEDIUM);
        Mockito.when(fixture.guard.evaluate(
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any()))
                .thenReturn(
                        // Outer loop: call 1 pauses the batch.
                        new ToolInvocationGuard.GuardDecision.PauseForApproval(frame),
                        // Pre-execution split: call 1 stays pending, call 2 is safe to run now.
                        new ToolInvocationGuard.GuardDecision.PauseForApproval(frame),
                        new ToolInvocationGuard.GuardDecision.Allow());
        Mockito.when(fixture.sequential.invoke(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(ToolResponse.success("pre-ok"));
        stubPersistWithPreExecuted(fixture, AgentResult.awaitingApproval(frame, "sess-1", List.of()));

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1, CALL_2)),
                        0);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertInstanceOf(AgentResult.AwaitingApproval.class, decision.result());
        // Only the safe call runs; the paused one is handed to the checkpoint.
        Mockito.verify(fixture.sequential).invoke("web_fetch", Map.of("url", "b"), "sess-1");
        verifyPersistWithPreExecuted(fixture);
    }

    // ================= execute(): delegated pause =================

    @Test
    void execute_delegatedPauseIsPersistedWithTheReplayableCall() throws Exception {
        Fixture fixture = new Fixture();
        allowEveryCall(fixture);
        String pauseObservation = UserMessages.delegationPaused("researcher", "search_tool", "{\"q\":\"x\"}");
        Mockito.when(fixture.sequential.execute(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ParallelToolCall call = invocation.getArgument(1);
                    // A delegated call that came back paused: the executor rewrites it into a
                    // pending delegation so the resume replays it after approval.
                    return ParallelToolCall.pending(
                                    call.callId(),
                                    ToolCapability.DELEGATE_TO_AGENT.toolName(),
                                    call.params(),
                                    call.paramsJson())
                            .withResult(pauseObservation, false);
                });
        stubPersistWithPreExecuted(
                fixture,
                AgentResult.awaitingApproval(
                        new ApprovalFrame("parallel|delegate_to_agent", Map.of(), RiskLevel.MEDIUM),
                        "sess-1",
                        List.of()));

        StepDecision decision = fixture.build(inlineExecutor())
                .execute(
                        context("sess-1", AutonomyLevel.FULL),
                        new ArrayList<>(),
                        batchStep(fixture.objectMapper, List.of(CALL_1)),
                        0);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertInstanceOf(AgentResult.AwaitingApproval.class, decision.result());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ParallelToolCall>> checkpointCalls = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, ToolResponse>> results = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(fixture.checkpointManager)
                .persistBatchWithPreExecuted(
                        ArgumentMatchers.any(),
                        checkpointCalls.capture(),
                        results.capture(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any(),
                        ArgumentMatchers.any());

        ParallelToolCall replayed = checkpointCalls.getValue().getFirst();
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), replayed.toolName());
        assertNull(replayed.observation());
        assertFalse(replayed.success());
        // The paused call is the one awaiting approval, so it must not be stored as a result.
        assertTrue(results.getValue().isEmpty());
    }

    // ================= redirectUnauthorizedCall: delegation build failure =================

    @Test
    void redirectUnauthorizedCall_keepsTheOriginalCallWhenDelegationCannotBeSerialized() throws Exception {
        Fixture fixture = new Fixture();
        ObjectMapper throwingMapper = Mockito.mock(ObjectMapper.class);
        Mockito.when(throwingMapper.writeValueAsString(ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("cannot serialize"));
        Mockito.when(fixture.delegationResolver.resolveOwner("coder", "web_search"))
                .thenReturn(Optional.of("researcher"));

        ParallelToolStepExecutor executor = new ParallelToolStepExecutor(
                throwingMapper,
                fixture.guard,
                fixture.checkpointManager,
                fixture.observers(),
                fixture.governance,
                fixture.rateLimitTracker,
                fixture.meterRegistry,
                fixture.sequential,
                poolOf(inlineExecutor()),
                fixture.categoryMapper,
                fixture.delegationResolver,
                Optional.empty(),
                60);

        var method = ParallelToolStepExecutor.class.getDeclaredMethod(
                "redirectUnauthorizedCall", AgentExecutionContext.class, ParallelToolCall.class);
        method.setAccessible(true);

        ParallelToolCall call = ParallelToolCall.pending("c1", "web_search", Map.of("q", "a"), "{}");
        ParallelToolCall result =
                (ParallelToolCall) method.invoke(executor, context("sess-1", AutonomyLevel.FULL), call);

        // A redirect that cannot be built must degrade to the original call, never to a partial one.
        assertSame(call, result);
    }
}
