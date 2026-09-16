package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.reasoning.error.LlmErrorClassificationService;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpoint;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.governance.BudgetWarningException;
import com.amsha.core.governance.GovernanceStrategy;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.ToolCallJsonKeys;
import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmContentFilterException;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmRateLimitException;
import com.amsha.llm.api.error.LlmToolCallException;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link EngineStepHandler}: degraded-response detection, raw-JSON FINISH
 * guard, schema budgets, budget events, and the exception classification matrix.
 */
public class EngineStepHandlerTest {

    private GovernanceStrategy governance;
    private Instance<AgentObserver> observers;
    private StatefulAgentStore agentStore;
    private ResponseRecoveryHintBuilder hintBuilder;
    private LlmExceptionRouter exceptionRouter;
    private ReasoningEngine engine;
    private EngineStepHandler handler;
    private AgentExecutionContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        governance = mock(GovernanceStrategy.class);
        observers = mock(Instance.class);
        when(observers.iterator()).thenReturn(List.<AgentObserver>of().iterator());
        agentStore = mock(StatefulAgentStore.class);
        hintBuilder = mock(ResponseRecoveryHintBuilder.class);
        exceptionRouter = mock(LlmExceptionRouter.class);
        engine = mock(ReasoningEngine.class);
        handler = new EngineStepHandler(governance, observers, agentStore, hintBuilder, exceptionRouter, 4, 6, 15, 10);
        context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "do things",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "42",
                null,
                Map.of());
        when(hintBuilder.buildDegradedResponseHint()).thenReturn("degraded-hint");
        when(hintBuilder.buildRawJsonInAnswerHint()).thenReturn("raw-json-hint");
        when(hintBuilder.getFallbackDirective()).thenReturn("fallback-directive");
    }

    private ReasoningStep.ToolCallStep toolCallStep(int outputTokens) {
        return new ReasoningStep.ToolCallStep(
                "thought", "calculator", Map.of(), null, 5, outputTokens, null, null, null, null, false, false);
    }

    private ReasoningStep.FinishStep finishStep(String answer) {
        return new ReasoningStep.FinishStep("done", Map.of(ToolCallJsonKeys.ANSWER, answer), 5, 20, false);
    }

    @Test
    void normalToolCallStepRecordsGovernanceAndProceeds() {
        ReasoningStep step = toolCallStep(50);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(step);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(step, decision.step());
        verify(governance).recordLlmCall("sess-1", 5, 50);
    }

    @Test
    void degradedTokenResponseRetriesWithHint() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(toolCallStep(1));

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 2, 1);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertEquals(2, decision.llmSchemaErrorCount());
        assertEquals("degraded-hint", decision.transientError());
        assertEquals(1, context.getTruncationRetryCount());
    }

    @Test
    void blankFinishAnswerIsTreatedAsDegraded() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(finishStep("   "));

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals("degraded-hint", decision.transientError());
    }

    @Test
    void rawJsonInFinishRetriesThenTerminatesAtBudget() {
        // "agent"+"goal" keys are the canonical raw-tool-JSON leak signature (RawJsonGuard)
        String json = "{\"agent\": \"coder\", \"goal\": \"write it\"}";
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenReturn(finishStep(json))
                .thenReturn(finishStep(json));

        StepDecision first = handler.processEngineStep(context, engine, new ArrayList<>(), null, 2, 1);
        assertEquals(DecisionType.CONTINUE, first.type());
        assertEquals(3, first.llmSchemaErrorCount());

        StepDecision second = handler.processEngineStep(context, engine, new ArrayList<>(), "hint", 3, 1);
        assertEquals(DecisionType.TERMINAL, second.type());
        assertEquals(AgentResult.FailureCategory.SCHEMA_ERROR, ((AgentResult.Failed) second.result()).category());
    }

    @Test
    void failedObservationIncrementsSchemaCountUntilBudget() {
        ReasoningStep.ObservationStep failure =
                new ReasoningStep.ObservationStep("obs", "schema broke", 0, 0, false, "E1", null, null, false);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(failure);

        StepDecision retry = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);
        assertEquals(DecisionType.CONTINUE, retry.type());

        StepDecision terminal = handler.processEngineStep(context, engine, new ArrayList<>(), null, 4, 1);
        assertEquals(DecisionType.TERMINAL, terminal.type());
        assertTrue(((AgentResult.Failed) terminal.result()).reason().contains("repeated formatting"));
    }

    @Test
    void successfulObservationLoopsWithoutSchemaPenalty() {
        ReasoningStep.ObservationStep success =
                new ReasoningStep.ObservationStep("obs", "noted", 0, 0, true, null, null, null, false);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(success);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 3, 1);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertEquals(3, decision.llmSchemaErrorCount());
        verify(governance, never()).recordLlmCall(anyString(), anyInt(), anyInt());
    }

    @Test
    void budgetWarningAnnotatesAndAddsToHistory() {
        ReasoningStep step = toolCallStep(50);
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(step);
        doThrow(new BudgetWarningException("80% of token budget used"))
                .when(governance)
                .recordLlmCall(anyString(), anyInt(), anyInt());

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.processEngineStep(context, engine, history, null, 0, 1);

        assertTrue(decision.step().observation().contains("BUDGET WARNING"));
        assertEquals(1, history.size());
    }

    @Test
    void budgetExceededTerminatesTheTurn() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(toolCallStep(50));
        doThrow(new BudgetExceededException("token budget exhausted"))
                .when(governance)
                .recordLlmCall(anyString(), anyInt(), anyInt());

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.processEngineStep(context, engine, history, null, 0, 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertEquals(AgentResult.FailureCategory.BUDGET_EXCEEDED, ((AgentResult.Failed) decision.result()).category());
    }

    @Test
    void nullEngineResponseIsTerminalSystemError() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(null);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(((AgentResult.Failed) decision.result()).reason().contains("null step"));
    }

    @Test
    void authenticationFailuresTerminateImmediately() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmAuthenticationException("bad key"));

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
    }

    @Test
    void malformedResponsesFollowSchemaBudget() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmMalformedResponseException("bad shape"));
        when(hintBuilder.buildSchemaCorrectionHint(anyString())).thenReturn("fix-schema");

        StepDecision retry = handler.processEngineStep(context, engine, new ArrayList<>(), null, 1, 1);
        assertEquals(DecisionType.CONTINUE, retry.type());

        StepDecision terminal = handler.processEngineStep(context, engine, new ArrayList<>(), null, 4, 1);
        assertEquals(DecisionType.TERMINAL, terminal.type());
    }

    @Test
    void rateLimitWithinAutoRetryWindowDelaysAndRetries() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmRateLimitException("slow down"));
        when(exceptionRouter.extractRetryAfter(anyString(), anyInt())).thenReturn(2);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertTrue(decision.isRateLimitHit());
    }

    @Test
    void rateLimitBeyondWindowReturnsAwaitingRetry() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmRateLimitException("slow down"));
        when(exceptionRouter.extractRetryAfter(anyString(), anyInt())).thenReturn(120);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.AwaitingApproval == false);
        assertEquals(120000, ((AgentResult.AwaitingRetry) decision.result()).retryAfterMs());
    }

    @Test
    void contentFilterViolationsTerminateWithSecurityCategory() {
        // Core tests cannot reference provider classifiers (no Maven edge); simulate one via the SPI.
        LlmErrorClassificationService.bindSharedForTest(new LlmErrorClassificationService(
                List.of(t -> new LlmContentFilterException("blocked by safety filters", t))));
        try {
            when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                    .thenThrow(new RuntimeException("blocked"));
            StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);
            assertEquals(DecisionType.TERMINAL, decision.type());
            assertEquals(
                    AgentResult.FailureCategory.SECURITY_POLICY, ((AgentResult.Failed) decision.result()).category());
        } finally {
            LlmErrorClassificationService.bindSharedForTest(null);
        }
    }

    @Test
    void networkErrorsProduceFriendlyTerminalMessage() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException(new ConnectException("refused")));

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(((AgentResult.Failed) decision.result()).reason().contains("Unable to reach the AI service"));
    }

    @Test
    void loadCheckpointStatusUsesCacheWhenClean() {
        context.setLastCheckpointStatus(CheckpointStatus.IN_PROGRESS);

        Optional<CheckpointStatus> status = handler.loadCheckpointStatus(context);

        assertEquals(Optional.of(CheckpointStatus.IN_PROGRESS), status);
        verify(agentStore, never()).load(anyString(), anyString());
    }

    @Test
    void loadCheckpointStatusReloadsWhenDirtyOrUnknown() {
        context.markStatusDirty();
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(cp.getStatus()).thenReturn(CheckpointStatus.COMPLETED);
        when(agentStore.load("sess-1", "coder")).thenReturn(Optional.of(cp));

        Optional<CheckpointStatus> status = handler.loadCheckpointStatus(context);

        assertEquals(Optional.of(CheckpointStatus.COMPLETED), status);
        assertEquals(CheckpointStatus.COMPLETED, context.lastCheckpointStatus());
        assertFalse(context.isStatusDirty() && status.isEmpty());
    }

    // --- Task #5: cover previously-missed REACHABLE branches (dead guards reported as findings) ---

    @Test
    void systemNoteIsAppendedToGoal() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "do things",
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "TELEGRAM",
                "42",
                "sys-note",
                Map.of());
        when(engine.nextStep(any(), anyString(), anyString(), anyList())).thenReturn(toolCallStep(50));

        StepDecision decision = handler.processEngineStep(ctx, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.PROCEED, decision.type());
        ArgumentCaptor<String> goalCaptor = ArgumentCaptor.forClass(String.class);
        verify(engine).nextStep(any(), anyString(), goalCaptor.capture(), anyList());
        assertTrue(goalCaptor.getValue().contains("sys-note"));
    }

    @Test
    void lenientModeUsesLenientSchemaBudget() {
        AgentExecutionContext ctx = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                "do things",
                AutonomyLevel.FULL,
                StructuredOutputMode.LENIENT,
                10,
                0,
                "TELEGRAM",
                "42",
                null,
                Map.of());
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmMalformedResponseException("bad shape"));
        when(hintBuilder.buildSchemaCorrectionHint(anyString())).thenReturn("fix-schema");

        StepDecision decision = handler.processEngineStep(ctx, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertEquals(1, decision.llmSchemaErrorCount());
    }

    @Test
    void hallucinatedAgentToolProducesRetryHint() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmToolCallException("attempted to call tool 'coder'"));
        when(exceptionRouter.extractUnavailableToolName(anyString())).thenReturn("coder");

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.CONTINUE, decision.type());
        verify(hintBuilder).buildHallucinatedAgentToolHint("coder");
    }

    @Test
    void finishUnavailableToolWithAnswerTerminatesAsFinished() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmToolCallException("attempted to call tool 'FINISH'"));
        when(exceptionRouter.extractUnavailableToolName(anyString())).thenReturn("FINISH");
        when(exceptionRouter.extractFailedGeneration(anyString())).thenReturn("gen");
        when(exceptionRouter.extractFinishAnswer(anyString())).thenReturn("Final answer here");

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(decision.result() instanceof AgentResult.Finished);
    }

    @Test
    void unavailableSandboxToolDelegatesToCoder() {
        assertDelegationHint(ToolCapability.SANDBOX_EXEC.toolName(), AgentRoleEnum.CODER.id());
    }

    @Test
    void unavailableBrowserToolDelegatesToResearcher() {
        assertDelegationHint(ToolCapability.BROWSER_NAVIGATE.toolName(), AgentRoleEnum.RESEARCHER.id());
    }

    @Test
    void unavailableGenericToolDelegatesToCoordinator() {
        assertDelegationHint("calculator", AgentRoleEnum.COORDINATOR.id());
    }

    @Test
    void unavailableNullToolDelegatesToCoordinator() {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmToolCallException("no tool name"));
        when(exceptionRouter.extractUnavailableToolName(anyString())).thenReturn(null);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertTrue(decision.transientError().contains("garuda(agent=" + AgentRoleEnum.COORDINATOR.id() + ")"));
    }

    private void assertDelegationHint(String toolName, String expectedRoleId) {
        when(engine.nextStep(any(), anyString(), anyString(), anyList()))
                .thenThrow(new LlmToolCallException("attempted to call tool '" + toolName + "'"));
        when(exceptionRouter.extractUnavailableToolName(anyString())).thenReturn(toolName);

        StepDecision decision = handler.processEngineStep(context, engine, new ArrayList<>(), null, 0, 1);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertTrue(decision.transientError().contains("garuda(agent=" + expectedRoleId + ")"));
    }
}
