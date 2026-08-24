package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private ReasoningStep.FinishStep newReasoningFinish(String answer) {
        return new ReasoningStep.FinishStep("done", Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, answer), 0, 0, false);
    }
}
