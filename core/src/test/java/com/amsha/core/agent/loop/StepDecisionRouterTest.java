package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link StepDecisionRouter} routing branches using mocked collaborators.
 */
class StepDecisionRouterTest {

    private FinishActionHandler finishActionHandler;
    private ToolStepHandler toolStepHandler;
    private ChatMemoryWriter writer;
    private EngineStepHandler engineStepHandler;
    private StepDecisionRouter router;
    private AgentExecutionContext context;

    @BeforeEach
    public void setUp() {
        finishActionHandler = mock(FinishActionHandler.class);
        toolStepHandler = mock(ToolStepHandler.class);
        writer = mock(ChatMemoryWriter.class);
        engineStepHandler = mock(EngineStepHandler.class);
        router = new StepDecisionRouter(finishActionHandler, toolStepHandler, writer, engineStepHandler);
        context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "do the thing",
                AutonomyLevel.FULL,
                null,
                10,
                0,
                "TELEGRAM",
                "123",
                null,
                Map.of());
    }

    @Test
    public void nullStepProceedsToNextTurn() {
        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), null, 0, 0, List.of());

        assertFalse(outcome.isTerminal());
        assertFalse(outcome.retryEngine());
        assertEquals(0, outcome.consecutiveFailures());
    }

    @Test
    public void terminalValidationStopsRouting() {
        AgentResult failed = AgentResult.failed("bad step", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt())).thenReturn(StepDecision.terminal(failed));

        ReasoningStep step = ReasoningStep.systemMessage("irrelevant");
        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), step, 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        assertEquals(failed, outcome.result());
    }

    @Test
    public void continueValidationProceedsToNextTurn() {
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.continueLoop("wait for approval", 0));

        ReasoningStep step = ReasoningStep.systemMessage("tool call pending");
        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), step, 2, 1, List.of());

        assertFalse(outcome.isTerminal());
        // continueLoop carries its own zero failure count through to the outcome
        assertEquals(0, outcome.consecutiveFailures());
    }

    @Test
    public void retryValidationReplacesCurrentStepAndLoops() {
        ReasoningStep corrected = ReasoningStep.systemMessage("corrected schema");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.retryValidation(3))
                .thenReturn(StepDecision.proceed(corrected));
        // After retry loop re-validates the corrected step; second call returns proceed with a
        // system-message step, which routes to invocation (mocked to proceed).
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(corrected));

        ReasoningStep original = ReasoningStep.systemMessage("broken schema");
        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome = router.route(context, history, original, 1, 0, List.of());

        assertFalse(outcome.isTerminal());
        assertEquals(3, outcome.consecutiveFailures());
    }

    @Test
    public void completedFinishSavesAnswerAndReturnsTerminalFinished() {
        AgentResult finished = AgentResult.finished("the answer", List.of());
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Completed(finished, "the answer"));

        ReasoningStep.FinishStep finish = newReasoningFinish("the answer");
        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome = router.route(context, history, finish, 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        assertTrue(((AgentResult.Finished) outcome.result()).answer().contains("the answer"));
        verify(writer).saveAnswer("sess-1", "the answer");
        assertFalse(history.isEmpty());
    }

    @Test
    public void correctionFinishRetriesEngineWithIncrementedSchemaErrors() {
        ReasoningStep corrected = ReasoningStep.systemMessage("corrected");
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Correction(corrected, "fix json"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, history, newReasoningFinish("{bad"), 1, 0, List.of());

        assertFalse(outcome.isTerminal());
        assertTrue(outcome.retryEngine());
        assertEquals(2, outcome.llmSchemaErrorCount());
        assertFalse(history.isEmpty());
    }

    @Test
    public void awaitingApprovalInvocationIsTerminalAndMarksStatusDirty() {
        ReasoningStep toolStep = ReasoningStep.systemMessage("needs approval");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.proceed(toolStep));
        AgentResult awaiting = AgentResult.awaitingApproval("some_tool", "{}", "sess-1", List.of());
        // Awaiting-approval results arrive on a non-terminal decision; the router then
        // marks status dirty and converts to a terminal outcome itself.
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(new StepDecision(DecisionType.PROCEED, awaiting, null, null, 0, 0, false, false));

        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), toolStep, 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        assertEquals(awaiting, outcome.result());
        assertTrue(context.isStatusDirty());
    }

    @Test
    public void statusDirtyWithResolvedApprovalInvokesTool() {
        context.markStatusDirty();
        when(engineStepHandler.loadCheckpointStatus(any())).thenReturn(Optional.empty());
        ReasoningStep toolStep = ReasoningStep.systemMessage("run it");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.proceed(toolStep));
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(toolStep));

        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), toolStep, 0, 0, List.of());

        assertFalse(outcome.isTerminal());
        verify(engineStepHandler).loadCheckpointStatus(context);
        assertNull(context.lastCheckpointStatus());
    }

    @Test
    public void haltedSession_proceedsToNextTurnWithTheIncomingFailureCount() {
        WorkspaceManager.haltSession("sess-1");
        try {
            ReasoningStep step = ReasoningStep.systemMessage("irrelevant");
            StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), step, 0, 7, List.of());

            assertFalse(outcome.isTerminal());
            assertFalse(outcome.retryEngine());
            assertEquals(7, outcome.consecutiveFailures());
            // Short-circuits before any validation or invocation.
            verifyNoInteractions(toolStepHandler, finishActionHandler);
        } finally {
            WorkspaceManager.clearHalted("sess-1");
        }
    }

    @Test
    public void proceedValidationReturningAFinishStep_loopsBackIntoTheFinishHandler() {
        ReasoningStep.FinishStep finish = newReasoningFinish("the answer");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt())).thenReturn(StepDecision.proceed(finish));
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Completed(
                        AgentResult.finished("the answer", List.of()), "the answer"));

        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, new ArrayList<>(), ReasoningStep.systemMessage("start"), 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        assertEquals("the answer", ((AgentResult.Finished) outcome.result()).answer());
    }

    @Test
    public void synthesizerCompletion_rebuildsTheAnswerFromTheFinishOutcome() {
        AgentExecutionContext synthesizerContext = new AgentExecutionContext(
                "sess-s",
                "user-1",
                "chat-1",
                AgentRoleEnum.SYNTHESIZER.toScopedName(),
                "synthesize the results",
                AutonomyLevel.FULL,
                null,
                10,
                0,
                null,
                null,
                null,
                Map.of());
        // The Completed outcome's answer and its embedded result deliberately disagree, so the
        // synthesizer arm is distinguishable from the plain `instanceof Finished` arm.
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Completed(
                        AgentResult.finished("from-the-result", List.of()), "the synthesised answer"));

        StepDecisionRouter.RoutingOutcome outcome =
                router.route(synthesizerContext, new ArrayList<>(), newReasoningFinish("x"), 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        assertEquals("the synthesised answer", ((AgentResult.Finished) outcome.result()).answer());
    }

    @Test
    public void retryLeak_recordsTheCorrectionAndRetriesTheEngine() {
        ReasoningStep corrected = ReasoningStep.systemMessage("retry after leak");
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.RetryLeak(corrected, "leak hint", "sandbox_exec"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, history, newReasoningFinish("{leaked}"), 1, 0, List.of());

        assertTrue(outcome.retryEngine());
        assertFalse(outcome.isTerminal());
        assertEquals("leak hint", outcome.transientError());
        assertEquals(2, outcome.llmSchemaErrorCount());
        assertEquals(1, history.size());
    }

    @Test
    public void salvageFailed_stampsTheHistoryOntoTheFailedResult() {
        AgentResult failed = AgentResult.failed("cannot salvage", AgentResult.FailureCategory.SCHEMA_ERROR, List.of());
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.SalvageFailed(failed));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, history, newReasoningFinish("{bad"), 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        AgentResult.Failed result = (AgentResult.Failed) outcome.result();
        assertEquals("cannot salvage", result.reason());
        assertEquals(AgentResult.FailureCategory.SCHEMA_ERROR, result.category());
        assertFalse(result.steps().isEmpty());
    }

    @Test
    public void salvageFailed_withANonFailedResult_returnsItUnchanged() {
        AgentResult exceeded = AgentResult.loopExceeded(4, List.of());
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.SalvageFailed(exceeded));

        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, new ArrayList<>(), newReasoningFinish("{bad"), 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        assertEquals(exceeded, outcome.result());
    }

    @Test
    public void executeLeakedTool_invokesTheParsedToolAndContinues() {
        ReasoningStep corrected = ReasoningStep.systemMessage("leaked tool recorded");
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.ExecuteLeakedTool(
                        "sandbox_exec", Map.of("code", "echo hi"), corrected));
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(null));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, history, newReasoningFinish("{leak}"), 0, 0, List.of());

        assertFalse(outcome.isTerminal());
        ArgumentCaptor<ReasoningStep> invoked = ArgumentCaptor.forClass(ReasoningStep.class);
        verify(toolStepHandler).invokeToolAndObserve(any(), anyList(), invoked.capture(), anyInt(), anyList());
        assertEquals("sandbox_exec", invoked.getValue().action());
        assertEquals(Map.of("code", "echo hi"), invoked.getValue().actionInput());
        assertEquals(1, history.size());
    }

    @Test
    public void injectToolCall_invokesTheInjectedToolAndContinues() {
        ReasoningStep corrected = ReasoningStep.systemMessage("injected tool recorded");
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.InjectToolCall(
                        "memory_search", Map.of("query", "nickname"), corrected));
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(null));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, history, newReasoningFinish("I do not know"), 0, 0, List.of());

        assertFalse(outcome.isTerminal());
        ArgumentCaptor<ReasoningStep> invoked = ArgumentCaptor.forClass(ReasoningStep.class);
        verify(toolStepHandler).invokeToolAndObserve(any(), anyList(), invoked.capture(), anyInt(), anyList());
        assertEquals("memory_search", invoked.getValue().action());
        assertEquals(1, history.size());
    }

    @Test
    public void unhandledFinishOutcome_fallsThroughToToolValidation() {
        // SynthesisCorrection is not a router concern — it must not be swallowed as terminal.
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.SynthesisCorrection(
                        ReasoningStep.systemMessage("synthesis"), "hint", "memory result"));
        ReasoningStep toolStep = ReasoningStep.systemMessage("run it");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.proceed(toolStep));
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(toolStep));

        StepDecisionRouter.RoutingOutcome outcome =
                router.route(context, new ArrayList<>(), newReasoningFinish("draft"), 0, 0, List.of());

        assertFalse(outcome.isTerminal());
        verify(toolStepHandler).validateTool(any(), anyList(), any(), anyInt());
    }

    @Test
    public void correctionWithABlankAnswer_usesTheGenericObservation() {
        ReasoningStep.FinishStep blankAnswer =
                new ReasoningStep.FinishStep("done", Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, "   "), 0, 0, false);
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Correction(blankAnswer, "retry"));

        List<ReasoningStep> history = new ArrayList<>();
        router.route(context, history, blankAnswer, 0, 0, List.of());

        String observation = history.getFirst().observation();
        assertTrue(observation.contains("Rejected FINISH answer"));
        assertFalse(observation.contains("Answer was:"));
    }

    @Test
    public void correctionWithNoActionInput_fallsBackToTheThought() {
        ReasoningStep.FinishStep noInput = new ReasoningStep.FinishStep("the original thought", null, 0, 0, false);
        when(finishActionHandler.handle(any(), anyList(), any(), anyInt()))
                .thenReturn(new FinishActionHandler.FinishOutcome.Correction(noInput, "retry"));

        List<ReasoningStep> history = new ArrayList<>();
        router.route(context, history, noInput, 0, 0, List.of());

        assertTrue(history.getFirst().observation().contains("the original thought"));
    }

    @Test
    public void awaitingApprovalThatResolvesAfterTheWalRetry_invokesTheTool() {
        context.markStatusDirty();
        when(engineStepHandler.loadCheckpointStatus(any()))
                .thenReturn(Optional.of(CheckpointStatus.AWAITING_APPROVAL))
                .thenReturn(Optional.of(CheckpointStatus.IN_PROGRESS));
        ReasoningStep toolStep = ReasoningStep.systemMessage("run it");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.proceed(toolStep));
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(toolStep));

        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), toolStep, 0, 0, List.of());

        assertFalse(outcome.isTerminal());
        assertEquals(CheckpointStatus.IN_PROGRESS, context.lastCheckpointStatus());
        verify(engineStepHandler, times(2)).loadCheckpointStatus(context);
    }

    @Test
    public void interruptedDuringTheWalRetrySleep_restoresTheInterruptFlagAndKeepsRouting() {
        context.markStatusDirty();
        // Interrupt the routing thread from inside the first status read, so the WAL-retry sleep
        // aborts immediately and takes its InterruptedException branch. This is deterministic —
        // no timing window — because Thread.sleep() re-checks the flag on entry.
        when(engineStepHandler.loadCheckpointStatus(any()))
                .thenAnswer(invocation -> {
                    Thread.currentThread().interrupt();
                    return Optional.of(CheckpointStatus.AWAITING_APPROVAL);
                })
                .thenReturn(Optional.of(CheckpointStatus.IN_PROGRESS));
        ReasoningStep toolStep = ReasoningStep.systemMessage("run it");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.proceed(toolStep));
        when(toolStepHandler.invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList()))
                .thenReturn(StepDecision.proceed(toolStep));

        StepDecisionRouter.RoutingOutcome outcome;
        try {
            outcome = router.route(context, new ArrayList<>(), toolStep, 0, 0, List.of());

            assertFalse(outcome.isTerminal());
            assertEquals(CheckpointStatus.IN_PROGRESS, context.lastCheckpointStatus());
            // The catch block restored the flag rather than swallowing it.
            assertTrue(Thread.currentThread().isInterrupted());
            verify(engineStepHandler, times(2)).loadCheckpointStatus(context);
        } finally {
            Thread.interrupted(); // never leak a set interrupt flag into another test
        }
    }

    @Test
    public void stillAwaitingApprovalAfterTheWalRetry_isTerminalWithASystemError() {
        context.markStatusDirty();
        when(engineStepHandler.loadCheckpointStatus(any())).thenReturn(Optional.of(CheckpointStatus.AWAITING_APPROVAL));
        ReasoningStep toolStep = ReasoningStep.systemMessage("run it");
        when(toolStepHandler.validateTool(any(), anyList(), any(), anyInt()))
                .thenReturn(StepDecision.proceed(toolStep));

        StepDecisionRouter.RoutingOutcome outcome = router.route(context, new ArrayList<>(), toolStep, 0, 0, List.of());

        assertTrue(outcome.isTerminal());
        AgentResult.Failed failed = (AgentResult.Failed) outcome.result();
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
        assertTrue(failed.reason().contains("State corrupted"));
        verify(toolStepHandler, never()).invokeToolAndObserve(any(), anyList(), any(), anyInt(), anyList());
    }

    private ReasoningStep.FinishStep newReasoningFinish(String answer) {
        return new ReasoningStep.FinishStep("done", Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, answer), 0, 0, false);
    }
}
