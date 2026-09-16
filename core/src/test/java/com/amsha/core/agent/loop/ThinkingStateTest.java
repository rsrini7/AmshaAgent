package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.structured.StructuredOutputException;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ThinkingState} - the single LLM-turn state of the runner loop.
 *
 * <p>These exercise the four escape hatches that make the state machine resilient: the
 * deterministic write-intent fallback, the no-progress breaker, the rate-limit retry budget and
 * the structured-output self-correction budget. Every collaborator is mocked; the state itself is
 * obtained from a real {@link RunnerServices} so the wiring under test is the production wiring.
 */
class ThinkingStateTest {

    private static final String STORE_DIRECTIVE = "The user wants to store data. Call ingest_document immediately.";

    private EngineStepHandler engine;
    private IntentClassificationService intentDetector;
    private CorrectionStrategy correctionStrategy;
    private ThinkingState thinkingState;

    @BeforeEach
    void setUp() {
        engine = mock(EngineStepHandler.class);
        intentDetector = mock(IntentClassificationService.class);
        correctionStrategy = mock(CorrectionStrategy.class);
        thinkingState = servicesWithMaxRateLimitRetries(2);
        // Default posture: the goal is not a memory write. Individual tests opt in.
        when(intentDetector.isWriteIntent(anyString())).thenReturn(false);
    }

    private ThinkingState servicesWithMaxRateLimitRetries(int maxRateLimitRetries) {
        return new RunnerServices(engine, null, null, correctionStrategy, null, intentDetector, maxRateLimitRetries)
                .thinkingState();
    }

    private void engineReturns(StepDecision decision) {
        when(engine.processEngineStep(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(decision);
    }

    private static AgentExecutionContext executionContext(String goal) {
        return new AgentExecutionContext(
                "session-1",
                "user-1",
                "chat-1",
                "coordinator",
                goal,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
    }

    private static StateContext stateContext(String goal, List<ReasoningStep> history) {
        return new StateContext(executionContext(goal), null, history);
    }

    private static ReasoningStep toolStep(String action) {
        return ReasoningStep.pending("a thought", action, Map.of("code", "echo hi"), 0, 0);
    }

    /**
     * Reads the step a {@link ToolExecutionState} was constructed with. The state deliberately keeps
     * it private, so reflection is the only way to assert the deterministic fallback payload.
     */
    private static ReasoningStep stepOf(ToolExecutionState state) throws Exception {
        Field field = ToolExecutionState.class.getDeclaredField("step");
        field.setAccessible(true);
        return (ReasoningStep) field.get(state);
    }

    /** Drives two consecutive no-progress iterations so the write-intent fallback fires. */
    private ReasoningStep writeFallbackStepFor(String goal) throws Exception {
        when(intentDetector.isWriteIntent(anyString())).thenReturn(true);
        engineReturns(StepDecision.retryEngine(null, 0));
        StateContext stateContext = stateContext(goal, new ArrayList<>());

        thinkingState.execute(stateContext);
        return stepOf(assertInstanceOf(ToolExecutionState.class, thinkingState.execute(stateContext)));
    }

    // ---------------------------------------------------------------- write-intent directive

    @Test
    void writeIntentOnEmptyHistory_injectsTheStoreDirective() {
        when(intentDetector.isWriteIntent(anyString())).thenReturn(true);
        engineReturns(StepDecision.proceed(toolStep(ToolCapability.HOST_EXEC.toolName())));
        StateContext stateContext = stateContext("remember my birthday", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(ToolExecutionState.class, next);
        ArgumentCaptor<String> transientError = ArgumentCaptor.forClass(String.class);
        verify(engine).processEngineStep(any(), any(), any(), transientError.capture(), anyInt(), anyInt());
        assertEquals(STORE_DIRECTIVE, transientError.getValue());
    }

    @Test
    void writeIntentWithExistingHistory_doesNotInjectTheStoreDirective() {
        when(intentDetector.isWriteIntent(anyString())).thenReturn(true);
        engineReturns(StepDecision.proceed(toolStep(ToolCapability.HOST_EXEC.toolName())));
        StateContext stateContext = stateContext(
                "remember my birthday", new ArrayList<>(List.of(toolStep(ToolCapability.HOST_EXEC.toolName()))));

        thinkingState.execute(stateContext);

        ArgumentCaptor<String> transientError = ArgumentCaptor.forClass(String.class);
        verify(engine).processEngineStep(any(), any(), any(), transientError.capture(), anyInt(), anyInt());
        assertNull(transientError.getValue());
    }

    @Test
    void nonWriteIntentOnEmptyHistory_doesNotInjectTheStoreDirective() {
        engineReturns(StepDecision.proceed(toolStep(ToolCapability.HOST_EXEC.toolName())));
        StateContext stateContext = stateContext("what is the weather", new ArrayList<>());

        thinkingState.execute(stateContext);

        ArgumentCaptor<String> transientError = ArgumentCaptor.forClass(String.class);
        verify(engine).processEngineStep(any(), any(), any(), transientError.capture(), anyInt(), anyInt());
        assertNull(transientError.getValue());
    }

    // ------------------------------------------------------------------------ no-progress breaker

    @Test
    void firstNoProgressIteration_armsTheFinishRetry() {
        engineReturns(StepDecision.continueLoop(null, 0));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(PreflightState.class, next);
        assertEquals(1, stateContext.noProgressCount());
        assertTrue(stateContext.executionContext().isForceFinishRetry());
    }

    @Test
    void historyGrowth_resetsNoProgressAndDisarmsFinishRetry() {
        when(engine.processEngineStep(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    List<ReasoningStep> history = invocation.getArgument(2);
                    history.add(toolStep(ToolCapability.HOST_EXEC.toolName()));
                    return StepDecision.continueLoop(null, 0);
                });
        StateContext stateContext = stateContext("do something", new ArrayList<>());
        stateContext.recordNoProgress();
        stateContext.executionContext().setForceFinishRetry(true);

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(PreflightState.class, next);
        assertEquals(0, stateContext.noProgressCount());
        assertFalse(stateContext.executionContext().isForceFinishRetry());
    }

    @Test
    void repeatedNoProgressWithWriteIntent_routesToTheDeterministicIngestFallback() throws Exception {
        when(intentDetector.isWriteIntent(anyString())).thenReturn(true);
        engineReturns(StepDecision.retryEngine(null, 0));
        StateContext stateContext = stateContext("remember this: buy milk", new ArrayList<>());

        thinkingState.execute(stateContext);
        RunnerState next = thinkingState.execute(stateContext);

        ReasoningStep fallback = stepOf(assertInstanceOf(ToolExecutionState.class, next));
        assertEquals(ToolCapability.INGEST_DOCUMENT.toolName(), fallback.action());
        assertEquals("user_memory", fallback.actionInput().get("label"));
        assertEquals("buy milk", fallback.actionInput().get("text"));
        assertEquals(0, stateContext.noProgressCount());
        assertFalse(stateContext.executionContext().isForceFinishRetry());
        assertNull(stateContext.transientError());
    }

    @Test
    void repeatedNoProgressWithoutWriteIntent_failsWithTheEngineReason() {
        engineReturns(StepDecision.continueLoop("tool schema was rejected", 0));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        thinkingState.execute(stateContext);
        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, stateContext.finalResult());
        assertEquals(AgentResult.FailureCategory.SCHEMA_ERROR, failed.category());
        assertEquals("The model made no progress repeatedly: tool schema was rejected", failed.reason());
    }

    @Test
    void repeatedNoProgressWithoutAReason_usesTheGenericDetail() {
        engineReturns(StepDecision.retryEngine(null, 0));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        thinkingState.execute(stateContext);
        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, stateContext.finalResult());
        assertEquals("The model returned no executable step repeatedly.", failed.reason());
    }

    @Test
    void repeatedNoProgressWithABlankReason_usesTheGenericDetail() {
        engineReturns(StepDecision.retryEngine("   ", 0));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        thinkingState.execute(stateContext);
        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, stateContext.finalResult());
        assertEquals("The model returned no executable step repeatedly.", failed.reason());
    }

    @Test
    void repeatedNoProgressWithWriteIntentButIngestAlreadyInHistory_doesNotFallBack() {
        when(intentDetector.isWriteIntent(anyString())).thenReturn(true);
        engineReturns(StepDecision.retryEngine(null, 0));
        List<ReasoningStep> history = new ArrayList<>(List.of(toolStep(ToolCapability.INGEST_DOCUMENT.toolName())));
        StateContext stateContext = stateContext("remember this", history);

        thinkingState.execute(stateContext);
        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        assertInstanceOf(AgentResult.Failed.class, stateContext.finalResult());
    }

    // ---------------------------------------------------------------------- write-fallback payload

    @Test
    void writeFallbackPayload_usesTheGoalWhenItHasNoDirectivePrefix() throws Exception {
        ReasoningStep fallback = writeFallbackStepFor("please note that my name is Ada");

        assertEquals("please note that my name is Ada", fallback.actionInput().get("text"));
    }

    @Test
    void writeFallbackPayload_stripsTheRememberPrefix() throws Exception {
        ReasoningStep fallback = writeFallbackStepFor("remember this: buy milk");

        assertEquals("buy milk", fallback.actionInput().get("text"));
    }

    @Test
    void writeFallbackPayload_stripsABareDirectiveAndFallsBackToTheRawGoal() throws Exception {
        ReasoningStep fallback = writeFallbackStepFor("remember");

        assertEquals("remember", fallback.actionInput().get("text"));
    }

    @Test
    void writeFallbackPayload_fallsBackToTheGenericTextForABlankGoal() throws Exception {
        ReasoningStep fallback = writeFallbackStepFor("   ");

        assertEquals(
                "User requested that this information be remembered.",
                fallback.actionInput().get("text"));
    }

    @Test
    void writeFallbackPayload_truncatesToTwelveThousandCharacters() throws Exception {
        ReasoningStep fallback = writeFallbackStepFor("x".repeat(13_000));

        assertEquals(12_000, String.valueOf(fallback.actionInput().get("text")).length());
    }

    // ----------------------------------------------------------------------------- rate limiting

    @Test
    void rateLimitBeyondBudget_failsWithSystemError() {
        thinkingState = servicesWithMaxRateLimitRetries(0);
        engineReturns(StepDecision.retryRateLimit("429 from provider", 0));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, stateContext.finalResult());
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
        assertEquals("Repeated rate limits.", failed.reason());
    }

    @Test
    void rateLimitWithinBudget_consumesARetryAndReentersPreflight() {
        engineReturns(StepDecision.retryRateLimit("429 from provider", 0));
        StateContext stateContext = stateContext("do something", new ArrayList<>());
        int startingIteration = stateContext.iteration();

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(PreflightState.class, next);
        assertEquals(1, stateContext.rateLimitRetryCount());
        assertEquals(startingIteration - 1, stateContext.iteration());
    }

    // -------------------------------------------------------------- structured-output self-correction

    @Test
    void structuredOutputBeyondBudget_failsWithLoopExceeded() {
        when(correctionStrategy.withinBudget(0)).thenReturn(false);
        doThrow(new StructuredOutputException("could not parse the model output"))
                .when(engine)
                .processEngineStep(any(), any(), any(), any(), anyInt(), anyInt());
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        AgentResult.LoopExceeded exceeded =
                assertInstanceOf(AgentResult.LoopExceeded.class, stateContext.finalResult());
        assertEquals(5, exceeded.maxIterations());
    }

    @Test
    void structuredOutputWithinBudget_injectsTheCorrectionHint() {
        when(correctionStrategy.withinBudget(0)).thenReturn(true);
        when(correctionStrategy.buildCorrectionHint(1, "could not parse the model output"))
                .thenReturn("Return a single JSON object.");
        doThrow(new StructuredOutputException("could not parse the model output"))
                .when(engine)
                .processEngineStep(any(), any(), any(), any(), anyInt(), anyInt());
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(PreflightState.class, next);
        assertEquals(1, stateContext.llmSchemaErrorCount());
        assertEquals("Return a single JSON object.", stateContext.transientError());
    }

    // ------------------------------------------------------------------------- engine decision arms

    @Test
    void finishedTerminalDecision_routesToCompletedState() {
        engineReturns(StepDecision.terminal(AgentResult.finished("All done.", List.of())));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(CompletedState.class, next);
        assertInstanceOf(AgentResult.Finished.class, stateContext.finalResult());
    }

    @Test
    void failedTerminalDecision_routesToFailedState() {
        engineReturns(StepDecision.terminal(
                AgentResult.failed("provider exploded", AgentResult.FailureCategory.SYSTEM_ERROR, List.of())));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(FailedState.class, next);
        assertInstanceOf(AgentResult.Failed.class, stateContext.finalResult());
    }

    @Test
    void continueDecision_propagatesTheTransientErrorAndSchemaCount() {
        engineReturns(StepDecision.retryEngine("schema rejected", 3));
        StateContext stateContext = stateContext("do something", new ArrayList<>());

        RunnerState next = thinkingState.execute(stateContext);

        assertInstanceOf(PreflightState.class, next);
        assertEquals("schema rejected", stateContext.transientError());
        assertEquals(3, stateContext.llmSchemaErrorCount());
    }
}
