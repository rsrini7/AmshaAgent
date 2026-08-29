package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.tool.DynamicToolLookup;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentTool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ToolStepValidator}: specialist redirection, known-tool checks,
 * and unknown-tool (ReAct keyword / hallucination) handling.
 */
public class ToolStepValidatorTest {

    private ToolRegistry toolRegistry;
    private Instance<AgentObserver> observers;
    private Instance<DynamicToolLookup> dynamicToolLookup;
    private ToolDelegationResolver delegationResolver;
    private ToolStepValidator validator;
    private AgentExecutionContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        observers = mock(Instance.class);
        dynamicToolLookup = mock(Instance.class);
        delegationResolver = mock(ToolDelegationResolver.class);
        validator = new ToolStepValidator(toolRegistry, observers, dynamicToolLookup, delegationResolver);
        context = new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coordinator",
                "just chat about things",
                AutonomyLevel.FULL,
                null,
                10,
                0,
                "TELEGRAM",
                "42",
                null,
                Map.of());
        when(dynamicToolLookup.isUnsatisfied()).thenReturn(true);
        when(observers.iterator()).thenReturn(List.<AgentObserver>of().iterator());
    }

    private ReasoningStep.ToolCallStep toolStep(String action) {
        return new ReasoningStep.ToolCallStep(
                "thought", action, Map.of("q", "x"), null, 0, 0, null, null, null, null, false, false);
    }

    @Test
    public void coordinatorGithubWithoutResourceRedirectsToResearcher() {
        StepDecision decision = validator.validate(context, new ArrayList<>(), toolStep("github"), "github", 0);

        assertNotNull(decision.step());
        assertEquals("garuda", decision.step().action());
        assertEquals("researcher", decision.step().actionInput().get("agent"));
    }

    @Test
    public void coordinatorSandboxRequestRedirectsToCoder() {
        StepDecision decision =
                validator.validate(context, new ArrayList<>(), toolStep("sandbox_exec"), "sandbox_exec", 0);

        assertEquals("garuda", decision.step().action());
        assertEquals("coder", decision.step().actionInput().get("agent"));
    }

    @Test
    public void coordinatorWebSearchRedirectsToResearcherButResearcherKeepsIt() {
        StepDecision coordinatorOutcome =
                validator.validate(context, new ArrayList<>(), toolStep("serper_web_search"), "serper_web_search", 0);
        assertEquals("researcher", coordinatorOutcome.step().actionInput().get("agent"));

        context = withRole("researcher");
        when(toolRegistry.findTool("serper_web_search")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(delegationResolver.resolveOwner("researcher", "serper_web_search")).thenReturn(Optional.empty());
        // researcher owning search tools: validator returns null = may proceed untouched
        assertNull(
                validator.validate(context, new ArrayList<>(), toolStep("serper_web_search"), "serper_web_search", 0));
    }

    @Test
    public void knownToolWithNoOwnerProceedsUntouched() {
        when(toolRegistry.findTool("calculator")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(delegationResolver.resolveOwner("coordinator", "calculator")).thenReturn(Optional.empty());

        assertNull(validator.validate(context, new ArrayList<>(), toolStep("calculator"), "calculator", 0));
    }

    @Test
    public void knownToolOwnedBySpecialistDelegates() {
        when(toolRegistry.findTool("sql_query")).thenReturn(Optional.of(mock(AgentTool.class)));
        when(delegationResolver.resolveOwner("coordinator", "sql_query")).thenReturn(Optional.of("analyst"));

        StepDecision decision = validator.validate(context, new ArrayList<>(), toolStep("sql_query"), "sql_query", 0);

        assertEquals("analyst", decision.step().actionInput().get("agent"));
    }

    @Test
    public void reactKeywordYieldsGuidanceAndContinuesLoop() {
        when(toolRegistry.findTool(any())).thenReturn(Optional.empty());
        when(dynamicToolLookup.isUnsatisfied()).thenReturn(true);
        when(toolRegistry.listTools()).thenReturn(List.of("web_search"));

        StepDecision decision = validator.validate(context, new ArrayList<>(), toolStep("THINK"), "THINK", 0);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertTrue(decision.transientError().contains("ReAct reasoning keyword"));
    }

    @Test
    public void hallucinatedToolFirstFailureRecordsHistoryAndContinues() {
        when(toolRegistry.findTool(any())).thenReturn(Optional.empty());
        when(dynamicToolLookup.isUnsatisfied()).thenReturn(true);
        when(toolRegistry.listTools()).thenReturn(List.of("web_search"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = validator.validate(context, history, toolStep("make_coffee"), "make_coffee", 0);

        assertEquals(DecisionType.CONTINUE, decision.type());
        assertNull(decision.transientError());
        assertEquals(1, history.size());
        assertEquals(
                "Tool 'make_coffee' does not exist. Available tools: [web_search].",
                history.getFirst().observation());
    }

    @Test
    public void repeatedHallucinatedToolsTerminateTheTurn() {
        when(toolRegistry.findTool(any())).thenReturn(Optional.empty());
        when(dynamicToolLookup.isUnsatisfied()).thenReturn(true);
        when(toolRegistry.listTools()).thenReturn(List.of("web_search"));

        List<ReasoningStep> history = new ArrayList<>();
        StepDecision decision = validator.validate(context, history, toolStep("make_coffee"), "make_coffee", 1);

        assertEquals(DecisionType.TERMINAL, decision.type());
        assertTrue(((AgentResult.Finished) decision.result()).answer().contains("repeated errors"));
    }

    @Test
    public void coordinatorBrowserAndBraveSearchRedirectsToResearcher() {
        StepDecision braveDecision =
                validator.validate(context, new ArrayList<>(), toolStep("brave_web_search"), "brave_web_search", 0);
        assertEquals("researcher", braveDecision.step().actionInput().get("agent"));

        StepDecision browserDecision =
                validator.validate(context, new ArrayList<>(), toolStep("browser_navigate"), "browser_navigate", 0);
        assertEquals("researcher", browserDecision.step().actionInput().get("agent"));
    }

    @Test
    public void researcherSandboxExecRedirectsToCoder() {
        AgentExecutionContext researcherCtx = withRole("researcher");
        StepDecision decision =
                validator.validate(researcherCtx, new ArrayList<>(), toolStep("sandbox_exec"), "sandbox_exec", 0);
        assertEquals("coder", decision.step().actionInput().get("agent"));
    }

    private AgentExecutionContext withRole(String role) {
        return new AgentExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                role,
                "just chat",
                AutonomyLevel.FULL,
                null,
                10,
                0,
                "TELEGRAM",
                "42",
                null,
                Map.of());
    }
}
