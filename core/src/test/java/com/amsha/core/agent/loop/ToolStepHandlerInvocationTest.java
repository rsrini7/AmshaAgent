package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.governance.RequiresApprovalException;
import com.amsha.core.message.UserMessages;
import com.amsha.core.observability.AgentObserver;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.RiskLevel;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Covers the four-phase invocation half of {@link ToolStepHandler}: the infrastructure-failure
 * short circuit, the auto-heal recovery matrix, guard decisions (blocked / repeated-blocked /
 * delegate fast-path / pause-for-approval), and the approval short circuit.
 *
 * <p>Companion to {@link ToolStepHandlerGuardTest}, which covers {@code validateTool} and the
 * deduplicate path. Kept separate so the strict-stub bookkeeping of the two concerns does not
 * interfere.
 */
class ToolStepHandlerInvocationTest {

    private static final String SANDBOX = ToolCapability.SANDBOX_EXEC.toolName();
    private static final String DELEGATE = ToolCapability.DELEGATE_TO_AGENT.toolName();
    private static final String GITHUB = ToolCapability.GITHUB.toolName();

    private Instance<AgentObserver> observers;
    private ToolInvocationGuard guard;
    private ToolInvoker invoker;
    private ChatMemoryWriter writer;
    private ApprovalCheckpointManager checkpointManager;
    private ParallelToolStepExecutor parallelExecutor;
    private ExecutionFailureRecoveryService recoveryService;
    private ToolStepEscalationHandler escalationHandler;
    private ToolStepHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        observers = mock(Instance.class);
        guard = mock(ToolInvocationGuard.class);
        invoker = mock(ToolInvoker.class);
        writer = mock(ChatMemoryWriter.class);
        checkpointManager = mock(ApprovalCheckpointManager.class);
        parallelExecutor = mock(ParallelToolStepExecutor.class);
        recoveryService = mock(ExecutionFailureRecoveryService.class);
        escalationHandler = mock(ToolStepEscalationHandler.class);

        handler = new ToolStepHandler(
                observers,
                guard,
                invoker,
                writer,
                checkpointManager,
                parallelExecutor,
                new SimpleMeterRegistry(),
                recoveryService,
                mock(ToolStepValidator.class),
                escalationHandler);
    }

    private static AgentExecutionContext context(String sessionId, String role, String goal) {
        return new AgentExecutionContext(
                sessionId,
                "user1",
                "chat1",
                role,
                goal,
                AutonomyLevel.FULL,
                StructuredOutputMode.NATIVE,
                5,
                0,
                "TELEGRAM",
                "chat1",
                null,
                Map.of());
    }

    /** Lets the guard fall through to PROCEED so the invocation phase is reached. */
    private void allowGuard() {
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Allow());
    }

    private void blockGuard(String observation) {
        when(guard.evaluate(anyString(), anyString(), any(), anyString(), any(), anyList(), anyString(), anyList()))
                .thenReturn(new ToolInvocationGuard.GuardDecision.Blocked(observation));
    }

    /** Stubs the nine-argument {@code attemptRecovery} used by the auto-heal hook. */
    private void recoveryReturns(ExecutionFailureRecoveryService.RecoveryResult result, String language) {
        when(recoveryService.attemptRecovery(
                        anyString(), anyString(), anyString(), any(), eq(language), any(), any(), any(), any()))
                .thenReturn(result);
    }

    /**
     * The escalation handler is typed on {@code ToolFailure} rather than {@code ToolResponse}, so the
     * declared type matters for both stubbing and captors.
     */
    private static ToolResponse.ToolFailure toolFailure(String output) {
        return new ToolResponse.ToolFailure(1, output, false, null, null, null);
    }

    // ── Infrastructure failure ───────────────────────────────────────────────

    @Test
    void infraFailureFromInvoker_terminatesWithSystemErrorAndInfraObservation() {
        AgentExecutionContext ctx = context("sess1", "coder", "run code");
        ReasoningStep step = ReasoningStep.pending("t", SANDBOX, Map.of("code", "int x"));
        allowGuard();
        when(invoker.invoke(eq(SANDBOX), any(), eq("sess1"))).thenReturn(ToolResponse.infraFailure("sandbox offline"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, decision.result());
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
        assertEquals("⚠️ sandbox offline", failed.reason());
        assertEquals(1, history.size());
        assertEquals("INFRA_FAILURE: sandbox offline", history.getFirst().observation());
        // An infrastructure failure is terminal - nothing is recorded for deduplication.
        verify(guard, never()).recordToolCall(any(), any(), any(), any());
    }

    // ── Auto-heal matrix ─────────────────────────────────────────────────────

    @Test
    void autoHealWithMissingLanguage_skipsRecoveryAndGenericEscalation() {
        AgentExecutionContext ctx = context("sess1", "coder", "run code");
        // No "language" key: extractParam yields "" and the recovery hook must bail out.
        ReasoningStep step = ReasoningStep.pending("t", SANDBOX, Map.of("code", "int x"));
        allowGuard();
        ToolResponse.ToolFailure failure = toolFailure("compile error");
        when(invoker.invoke(eq(SANDBOX), any(), anyString())).thenReturn(failure);
        when(recoveryService.shouldAttemptRecovery("coder", SANDBOX, failure)).thenReturn(true);

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        verify(recoveryService, never())
                .attemptRecovery(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
        // Auto-heal already "attempted" escalation, so the generic hook is suppressed.
        verify(escalationHandler, never()).attemptGenericEscalation(any(), anyString(), any(), any());
        assertEquals(DecisionType.PROCEED, decision.type());
        verify(invoker, times(1)).invoke(eq(SANDBOX), any(), anyString());
    }

    @Test
    void autoHealFixedCodeFailingPreExecutionValidation_escalatesWithoutReExecution() {
        AgentExecutionContext ctx = context("sess1", "coder", "run code");
        ReasoningStep step = ReasoningStep.pending("t", SANDBOX, Map.of("code", "int x", "language", "java"));
        allowGuard();
        ToolResponse.ToolFailure failure = toolFailure("compile error");
        when(invoker.invoke(eq(SANDBOX), any(), anyString())).thenReturn(failure);
        when(recoveryService.shouldAttemptRecovery("coder", SANDBOX, failure)).thenReturn(true);
        recoveryReturns(ExecutionFailureRecoveryService.RecoveryResult.success("public class Main {}", 1), "java");
        when(recoveryService.validateForExecution("public class Main {}", "java"))
                .thenReturn("Java code has type declaration but no main() method");
        when(escalationHandler.attemptEscalationAfterHealFailure(eq(ctx), any(), eq(failure)))
                .thenReturn(ToolResponse.success("escalated"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        verify(escalationHandler).attemptEscalationAfterHealFailure(eq(ctx), any(), eq(failure));
        // Pre-execution validation rejected the fix, so podman is never invoked a second time.
        verify(invoker, times(1)).invoke(eq(SANDBOX), any(), anyString());
        assertEquals(DecisionType.PROCEED, decision.type());
    }

    @Test
    void autoHealReExecutionAlsoFails_escalatesWithTheRetryFailure() {
        AgentExecutionContext ctx = context("sess1", "coder", "run code");
        ReasoningStep step = ReasoningStep.pending("t", SANDBOX, Map.of("code", "int x", "language", "java"));
        allowGuard();
        ToolResponse.ToolFailure failure = toolFailure("compile error");
        when(invoker.invoke(eq(SANDBOX), any(), anyString()))
                .thenReturn(failure, ToolResponse.failure(1, "still broken"));
        when(recoveryService.shouldAttemptRecovery("coder", SANDBOX, failure)).thenReturn(true);
        recoveryReturns(ExecutionFailureRecoveryService.RecoveryResult.success("int y", 1), "java");
        when(recoveryService.validateForExecution("int y", "java")).thenReturn(null);
        when(escalationHandler.attemptEscalationAfterHealFailure(eq(ctx), any(), any()))
                .thenReturn(ToolResponse.success("escalated"));

        List<ReasoningStep> history = new ArrayList<>();
        handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        ArgumentCaptor<ToolResponse.ToolFailure> escalatedWith =
                ArgumentCaptor.forClass(ToolResponse.ToolFailure.class);
        verify(escalationHandler).attemptEscalationAfterHealFailure(eq(ctx), any(), escalatedWith.capture());
        assertEquals("still broken", escalatedWith.getValue().output());
        verify(invoker, times(2)).invoke(eq(SANDBOX), any(), anyString());
    }

    @Test
    void autoHealRecoveryFailure_escalatesWithTheOriginalFailure() {
        AgentExecutionContext ctx = context("sess1", "coder", "run code");
        ReasoningStep step = ReasoningStep.pending("t", SANDBOX, Map.of("code", "int x", "language", "java"));
        allowGuard();
        ToolResponse.ToolFailure failure = toolFailure("compile error");
        when(invoker.invoke(eq(SANDBOX), any(), anyString())).thenReturn(failure);
        when(recoveryService.shouldAttemptRecovery("coder", SANDBOX, failure)).thenReturn(true);
        recoveryReturns(
                ExecutionFailureRecoveryService.RecoveryResult.failure("Debug delegation failed: timeout"), "java");
        when(escalationHandler.attemptEscalationAfterHealFailure(eq(ctx), any(), eq(failure)))
                .thenReturn(ToolResponse.success("escalated"));

        handler.invokeToolAndObserve(ctx, new ArrayList<>(), step, 0, List.of());

        verify(escalationHandler).attemptEscalationAfterHealFailure(eq(ctx), any(), eq(failure));
        verify(recoveryService, never()).validateForExecution(anyString(), anyString());
        verify(invoker, times(1)).invoke(eq(SANDBOX), any(), anyString());
    }

    @Test
    void autoHealReExecutionBlockedByGuard_surfacesGuardFailureToEscalation() {
        AgentExecutionContext ctx = context("sess1", "coder", "run code");
        ReasoningStep step = ReasoningStep.pending("t", SANDBOX, Map.of("code", "int x", "language", "java"));
        allowGuard();
        ToolResponse.ToolFailure failure = toolFailure("compile error");
        ApprovalFrame frame = new ApprovalFrame(SANDBOX, Map.of(), RiskLevel.MEDIUM);
        // First invocation fails; the re-execution raises RequiresApprovalException, which
        // invokeTool turns into a ShortCircuit that invokeToolWithParams converts to a failure.
        when(invoker.invoke(eq(SANDBOX), any(), anyString()))
                .thenReturn(failure)
                .thenThrow(new RequiresApprovalException(frame, "sess1"));
        when(recoveryService.shouldAttemptRecovery("coder", SANDBOX, failure)).thenReturn(true);
        recoveryReturns(ExecutionFailureRecoveryService.RecoveryResult.success("int y", 1), "java");
        when(recoveryService.validateForExecution("int y", "java")).thenReturn(null);
        when(checkpointManager.persist(eq(ctx), eq(frame), anyList(), eq("TELEGRAM"), eq("chat1")))
                .thenReturn(AgentResult.awaitingApproval(frame, "sess1", List.of()));
        when(escalationHandler.attemptEscalationAfterHealFailure(eq(ctx), any(), any()))
                .thenReturn(ToolResponse.success("escalated"));

        handler.invokeToolAndObserve(ctx, new ArrayList<>(), step, 0, List.of());

        ArgumentCaptor<ToolResponse.ToolFailure> escalatedWith =
                ArgumentCaptor.forClass(ToolResponse.ToolFailure.class);
        verify(escalationHandler).attemptEscalationAfterHealFailure(eq(ctx), any(), escalatedWith.capture());
        assertTrue(
                escalatedWith.getValue().output().startsWith("Auto-heal re-execution blocked by guard: "),
                "guard short circuit must be reported, not silently swallowed");
    }

    @Test
    void genericEscalationApplied_replacesTheResponseAndRecordsSuccess() {
        AgentExecutionContext ctx = context("sess1", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", "my_tool", Map.of("k", "v"));
        allowGuard();
        ToolResponse.ToolFailure failure = toolFailure("transient");
        when(invoker.invoke(eq("my_tool"), any(), anyString())).thenReturn(failure);
        when(escalationHandler.attemptGenericEscalation(eq(ctx), eq("my_tool"), any(), eq(failure)))
                .thenReturn(ToolResponse.success("recovered"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals("recovered", history.getFirst().observation());
        assertEquals(Boolean.TRUE, history.getFirst().success());
        verify(guard).recordToolCall(eq("sess1"), eq("my_tool"), any(), eq("recovered"));
    }

    @Test
    void unexpectedInvocationException_isConvertedToAToolFailure() {
        AgentExecutionContext ctx = context("sess1", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", "my_tool", Map.of());
        allowGuard();
        when(invoker.invoke(eq("my_tool"), any(), anyString())).thenThrow(new IllegalStateException("transport reset"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        // Any non-approval exception becomes a ToolFailure rather than escaping the loop.
        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(Boolean.FALSE, history.getFirst().success());
        assertTrue(
                history.getFirst().observation().contains("transport reset"),
                history.getFirst().observation());
    }

    // ── Approval short circuit ───────────────────────────────────────────────

    @Test
    void requiresApprovalException_shortCircuitsToThePersistedCheckpoint() {
        AgentExecutionContext ctx = context("sess1", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", GITHUB, Map.of("target", "rsrini7"));
        allowGuard();
        ApprovalFrame frame = new ApprovalFrame(GITHUB, Map.of("target", "rsrini7"), RiskLevel.MEDIUM);
        when(invoker.invoke(eq(GITHUB), any(), anyString())).thenThrow(new RequiresApprovalException(frame, "sess1"));
        when(checkpointManager.persist(eq(ctx), eq(frame), anyList(), eq("TELEGRAM"), eq("chat1")))
                .thenReturn(AgentResult.awaitingApproval(frame, "sess1", List.of()));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertInstanceOf(AgentResult.AwaitingApproval.class, decision.result());
        ArgumentCaptor<List<ReasoningStep>> persisted = ArgumentCaptor.forClass(List.class);
        verify(checkpointManager).persist(eq(ctx), eq(frame), persisted.capture(), eq("TELEGRAM"), eq("chat1"));
        assertEquals(1, persisted.getValue().size());
        assertSame(step, persisted.getValue().getFirst());
        // The pause path must not append to the caller's live history.
        assertEquals(0, history.size());
    }

    @Test
    void delegatedPauseThatCannotBePersisted_terminatesWithSystemError() {
        AgentExecutionContext ctx = context("sess1", "coordinator", "delegate this");
        ReasoningStep step = ReasoningStep.pending("t", DELEGATE, Map.of("agent", "saraswati"));
        allowGuard();
        String paused = UserMessages.delegationPaused(
                "saraswati", "web_fetch", "[{\"toolName\":\"web_fetch\",\"params\":{\"url\":\"https://x\"}}]");
        when(invoker.invoke(eq(DELEGATE), any(), anyString())).thenReturn(ToolResponse.success(paused));
        when(checkpointManager.persistDelegatedWait(eq(ctx), eq(DELEGATE), any(), anyList()))
                .thenReturn(false);

        StepDecision decision = handler.invokeToolAndObserve(ctx, new ArrayList<>(), step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, decision.result());
        assertEquals("Could not persist delegated wait state.", failed.reason());
        assertEquals(AgentResult.FailureCategory.SYSTEM_ERROR, failed.category());
    }

    // ── Blocked guard decisions ──────────────────────────────────────────────

    @Test
    void blockedGuardDecision_appendsFailedObservationAndContinuesLoop() {
        AgentExecutionContext ctx = context("sess1", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", "risky_tool", Map.of("a", "b"));
        blockGuard("Tool is not permitted");

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertEquals(1, history.size());
        assertEquals("Tool is not permitted", history.getFirst().observation());
        assertEquals(Boolean.FALSE, history.getFirst().success());
        verifyNoInteractions(invoker);
    }

    @Test
    void secondIdenticalBlockedCall_terminatesWithSchemaError() {
        AgentExecutionContext ctx = context("sess1", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", "risky_tool", Map.of("a", "b"));
        blockGuard("Tool is not permitted");

        List<ReasoningStep> history = new ArrayList<>();
        // The identical call already failed once in this turn.
        history.add(step.withObservation("Tool is not permitted", false));

        StepDecision decision = handler.invokeToolAndObserve(ctx, history, step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());
        AgentResult.Failed failed = assertInstanceOf(AgentResult.Failed.class, decision.result());
        assertEquals(AgentResult.FailureCategory.SCHEMA_ERROR, failed.category());
        assertEquals("Tool call blocked repeatedly: Tool is not permitted", failed.reason());
        verifyNoInteractions(invoker);
    }

    @Test
    void githubBlockedInsideDelegateSession_fastPathsToFinish() {
        AgentExecutionContext ctx = context("parent:delegate:researcher:42", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", GITHUB, Map.of("query", "x"));
        blockGuard("GitHub is blocked here");

        StepDecision decision = handler.invokeToolAndObserve(ctx, new ArrayList<>(), step, 0, List.of());

        assertEquals(DecisionType.TERMINAL, decision.type());
        AgentResult.Finished finished = assertInstanceOf(AgentResult.Finished.class, decision.result());
        assertEquals("GitHub is blocked here", finished.answer());
        verifyNoInteractions(invoker);
    }

    @Test
    void githubBlockedOutsideDelegateSession_doesNotFastPath() {
        // Same blocked decision, but no delegate sentinel: the loop must continue instead.
        AgentExecutionContext ctx = context("sess1", "researcher", "do thing");
        ReasoningStep step = ReasoningStep.pending("t", GITHUB, Map.of("query", "x"));
        blockGuard("GitHub is blocked here");

        StepDecision decision = handler.invokeToolAndObserve(ctx, new ArrayList<>(), step, 0, List.of());

        assertEquals(DecisionType.CONTINUE, decision.type());
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    void parallelBatchStep_skipsValidationEntirely() {
        AgentExecutionContext ctx = context("sess1", "researcher", "batch");
        ReasoningStep batch = ReasoningStep.parallelBatch("t", Map.of("batch", List.of()), 0, 0);

        StepDecision decision = handler.validateTool(ctx, new ArrayList<>(), batch, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
        assertSame(batch, decision.step());
    }

    @Test
    void hallucinatedFinish_isRescuedIntoAFinishStep() {
        AgentExecutionContext ctx = context("sess1", "researcher", "answer me");
        // action == FINISH with an "answer" key is the shape the model hallucinates as a tool call.
        ReasoningStep step = ReasoningStep.pending("thought", ReasoningStep.FINISH, Map.of("answer", "42"));

        StepDecision decision = handler.validateTool(ctx, new ArrayList<>(), step, 0);

        assertEquals(DecisionType.PROCEED, decision.type());
        assertEquals(ReasoningStep.FINISH, decision.step().action());
        assertEquals("42", decision.step().actionInput().get("answer"));
    }
}
