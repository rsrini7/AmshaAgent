package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the persona-escalation glue extracted out of {@code ToolStepHandler}.
 *
 * <p>Two things matter here and are asserted explicitly rather than incidentally:
 * <ol>
 *   <li>the escalation target is only attempted when one was actually resolved, and</li>
 *   <li>when escalation does not happen the <em>original</em> failure object is handed back
 *       unchanged (identity, not a copy) so upstream failure handling keeps its diagnostics.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ToolStepEscalationHandlerTest {

    private static final String ROLE = "coordinator";
    private static final String TARGET = "researcher";
    private static final String GOAL = "fix the build";

    @Mock
    private PersonaEscalationService escalationService;

    private ToolStepEscalationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ToolStepEscalationHandler(escalationService);
    }

    private static AgentExecutionContext context() {
        return new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                ROLE,
                GOAL,
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                10,
                0,
                "telegram",
                "platform-9",
                "note",
                Map.of());
    }

    private static ToolResponse.ToolFailure failure(String output) {
        return (ToolResponse.ToolFailure) ToolResponse.failure(1, output);
    }

    /** Matches the 11-arg service call without pinning individual values. */
    private void stubEscalation(PersonaEscalationService.EscalationResult result) {
        when(escalationService.attemptEscalation(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenReturn(result);
    }

    // ================= attemptEscalationAfterHealFailure =================

    @Test
    void healFailureEscalatesWhenATargetRoleIsResolved() {
        ToolResponse.ToolFailure retryFailure = failure("syntax error persists");
        when(escalationService.determineEscalationTarget(ROLE, retryFailure.output(), GOAL))
                .thenReturn(TARGET);
        stubEscalation(PersonaEscalationService.EscalationResult.success(TARGET, "repaired", 1));

        ToolResponse response = handler.attemptEscalationAfterHealFailure(context(), Map.of(), retryFailure);

        assertTrue(response.success());
        assertEquals("repaired", response.output());
    }

    @Test
    void healFailureReturnsTheOriginalFailureWhenNoTargetRoleExists() {
        ToolResponse.ToolFailure retryFailure = failure("unrecoverable");
        when(escalationService.determineEscalationTarget(ROLE, retryFailure.output(), GOAL))
                .thenReturn(null);

        ToolResponse response = handler.attemptEscalationAfterHealFailure(context(), Map.of(), retryFailure);

        // Same instance, not a rebuilt copy - the caller relies on the original diagnostics.
        assertSame(retryFailure, response);
        verifyNoMoreInteractions(escalationService);
    }

    // ================= attemptEscalation =================

    @Test
    void successfulEscalationReturnsTheEscalatedResult() {
        stubEscalation(PersonaEscalationService.EscalationResult.success(TARGET, "escalated output", 2));

        ToolResponse response = handler.attemptEscalation(context(), Map.of(), failure("boom"), TARGET);

        assertTrue(response.success());
        assertEquals("escalated output", response.output());
    }

    @Test
    void failedEscalationFallsBackToTheOriginalFailure() {
        ToolResponse.ToolFailure original = failure("boom");
        stubEscalation(PersonaEscalationService.EscalationResult.failure("no such persona"));

        ToolResponse response = handler.attemptEscalation(context(), Map.of(), original, TARGET);

        assertSame(original, response);
    }

    @Test
    void escalationForwardsTheFullContextAndExtractedCodeParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "print('hi')");
        params.put("language", "python");
        stubEscalation(PersonaEscalationService.EscalationResult.failure("nope"));

        handler.attemptEscalation(context(), params, failure("boom"), TARGET);

        verify(escalationService)
                .attemptEscalation(
                        "sess-1",
                        "user-1",
                        "chat-1",
                        ROLE,
                        TARGET,
                        "boom",
                        GOAL,
                        "print('hi')",
                        "python",
                        "telegram",
                        "platform-9");
    }

    @Test
    void escalationStringifiesNonStringParamsAndBlanksNulls() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", 42);
        params.put("language", null);
        stubEscalation(PersonaEscalationService.EscalationResult.failure("nope"));

        handler.attemptEscalation(context(), params, failure("boom"), TARGET);

        // A non-String `code` is stringified; a null `language` degrades to empty, never "null".
        verify(escalationService)
                .attemptEscalation(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("42"),
                        eq(""),
                        anyString(),
                        anyString());
    }

    @Test
    void escalationTreatsAbsentParamsAsEmptyStrings() {
        stubEscalation(PersonaEscalationService.EscalationResult.failure("nope"));

        handler.attemptEscalation(context(), Map.of(), failure("boom"), TARGET);

        verify(escalationService)
                .attemptEscalation(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq(""),
                        eq(""),
                        anyString(),
                        anyString());
    }

    // ================= attemptGenericEscalation =================

    @Test
    void genericEscalationIsSkippedWhenNotApplicable() {
        ToolResponse.ToolFailure failure = failure("boom");
        when(escalationService.shouldAttemptEscalation(ROLE, "bash", failure)).thenReturn(false);

        ToolResponse response = handler.attemptGenericEscalation(context(), "bash", Map.of(), failure);

        assertNull(response);
        // The gate must short-circuit: no target resolution, no escalation attempt.
        verifyNoMoreInteractions(escalationService);
    }

    @Test
    void genericEscalationReturnsNullWhenNoTargetRoleIsResolved() {
        ToolResponse.ToolFailure failure = failure("boom");
        when(escalationService.shouldAttemptEscalation(ROLE, "bash", failure)).thenReturn(true);
        when(escalationService.determineEscalationTarget(ROLE, failure.output(), GOAL))
                .thenReturn(null);

        ToolResponse response = handler.attemptGenericEscalation(context(), "bash", Map.of(), failure);

        assertNull(response);
        verifyNoMoreInteractions(escalationService);
    }

    @Test
    void genericEscalationEscalatesWhenApplicable() {
        ToolResponse.ToolFailure failure = failure("boom");
        when(escalationService.shouldAttemptEscalation(ROLE, "bash", failure)).thenReturn(true);
        when(escalationService.determineEscalationTarget(ROLE, failure.output(), GOAL))
                .thenReturn(TARGET);
        stubEscalation(PersonaEscalationService.EscalationResult.success(TARGET, "handled", 1));

        ToolResponse response = handler.attemptGenericEscalation(context(), "bash", Map.of(), failure);

        assertTrue(response.success());
        assertEquals("handled", response.output());
    }
}
