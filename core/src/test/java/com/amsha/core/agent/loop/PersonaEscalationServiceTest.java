package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("java:S1192")
class PersonaEscalationServiceTest {

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    Instance<AgentLoop> agentLoopInstance;

    @Mock
    AgentLoop agentLoop;

    @Mock
    GroupConsultationService groupConsultationService;

    @InjectMocks
    PersonaEscalationService service;

    @BeforeEach
    void setUp() {
        service.escalationEnabled = true;
        service.maxEscalationDepth = 2;
        service.agentLoop = agentLoopInstance;
        when(agentLoopInstance.get()).thenReturn(agentLoop);
    }

    @Test
    void shouldAttemptEscalation_enabled_failure() {
        ToolResponse response = ToolResponse.failure(1, "error");
        assertTrue(service.shouldAttemptEscalation("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptEscalation_disabled_whenFeatureOff() {
        service.escalationEnabled = false;
        ToolResponse response = ToolResponse.failure(1, "error");
        assertFalse(service.shouldAttemptEscalation("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptEscalation_disabled_forInfraFailure() {
        ToolResponse response = ToolResponse.infraFailure("podman offline");
        assertFalse(service.shouldAttemptEscalation("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptEscalation_disabled_forSuccess() {
        ToolResponse response = ToolResponse.success("output");
        assertFalse(service.shouldAttemptEscalation("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void determineEscalationTarget_brahma_unknownApi_toSaraswati() {
        String target = service.determineEscalationTarget("coder", "cannot find symbol: List", "use List");
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_brahma_unknownPackage_toSaraswati() {
        String target = service.determineEscalationTarget("coder", "package com.example does not exist", null);
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_brahma_architectureQuestion_toBrihaspati() {
        String target = service.determineEscalationTarget("coder", "which approach should I use", null);
        assertEquals("architect", target);
    }

    @Test
    void determineEscalationTarget_sandboxCodeError_escalatesToDebugger() {
        String target = service.determineEscalationTarget("coder", "simple syntax error in line 5", null);
        assertEquals("debugger", target);
    }

    @Test
    void attemptEscalation_exceedsMaxDepth() {
        var res = service.attemptEscalation(
                "sess-1", "usr-1", "chat-1", "coder", "researcher", "err", "goal", null, null, "cli", "cli", 3);
        assertFalse(res.success());
        assertEquals("Max escalation depth reached", res.errorIfFailed());
    }

    @Test
    void attemptEscalation_pingPongDetected() {
        List<String> chain = new ArrayList<>(List.of("researcher->coder", "coder->researcher", "researcher->coder"));
        var res = service.attemptEscalation(
                "sess-1", "usr-1", "chat-1", "coder", "researcher", "err", "goal", null, null, "cli", "cli", 1, chain);
        assertFalse(res.success());
        assertTrue(res.errorIfFailed().contains("ping-pong"));
    }

    @Test
    void attemptEscalation_unknownTargetRole() {
        var res = service.attemptEscalation(
                "sess-1", "usr-1", "chat-1", "coder", "nonexistent_role", "err", "goal", null, null, "cli", "cli");
        assertFalse(res.success());
        assertTrue(res.errorIfFailed().contains("Unknown target persona"));
    }

    @Test
    void attemptEscalation_success() {
        AgentResult.Finished finished = new AgentResult.Finished("research answer", List.of(), 5, 5);
        when(agentLoop.run(anyString(), eq("researcher"), anyString(), eq(reasoningEngine), anyInt()))
                .thenReturn(finished);

        var res = service.attemptEscalation(
                "sess-1", "usr-1", "chat-1", "coder", "researcher", "err", "goal", null, null, "cli", "cli");
        assertTrue(res.success());
        assertEquals("researcher", res.targetPersona());
        assertEquals("research answer", res.result());
    }

    @Test
    void testGroupConsultationDelegation() {
        when(groupConsultationService.isGroupLead("coordinator")).thenReturn(true);
        assertTrue(service.isGroupLead("coordinator"));

        GroupConsultationService.TriageResult expectedTriage = mock(GroupConsultationService.TriageResult.class);
        when(groupConsultationService.triage("coordinator", "goal")).thenReturn(expectedTriage);
        assertEquals(expectedTriage, service.triageGroupConsultation("coordinator", "goal"));
    }

    @Test
    void buildEscalationGoal_containsSourceAndTarget() {
        String goal = service.buildEscalationGoal("coder", "researcher", "cannot find symbol", "use List", null, null);
        assertTrue(goal.contains("ESCALATION FROM Brahma (The Creator)"));
        assertTrue(goal.contains("Saraswati (The Researcher)"));
    }

    @Test
    void determineEscalationTarget_debuggerRules() {
        assertEquals("researcher", service.determineEscalationTarget("debugger", "unable to diagnose error", null));
        assertEquals("coder", service.determineEscalationTarget("debugger", "fundamental flaw", null));
        assertEquals(
                "optimizer",
                service.determineEscalationTarget("debugger", "error in algorithm", "complex logic obstacle"));
        assertEquals(
                "sentinel",
                service.determineEscalationTarget("debugger", "vulnerability detected", "security injection"));
    }

    @Test
    void determineEscalationTarget_architectAndResearcherRules() {
        assertEquals("coder", service.determineEscalationTarget("architect", "design ready", "implement the feature"));
        assertEquals(
                "researcher",
                service.determineEscalationTarget("architect", "design unclear", "research best practices"));
        assertEquals(
                "coder",
                service.determineEscalationTarget("researcher", "findings attached", "write code for solution"));
    }

    @Test
    void determineEscalationTarget_nullOrUniversalFallback() {
        assertNull(service.determineEscalationTarget(null, "error", "goal"));
        assertNull(service.determineEscalationTarget("coder", null, "goal"));
        assertEquals(
                "debugger", service.determineEscalationTarget("unknown_role", "compilation error in main.java", null));
    }

    @Test
    void testFormatAgentResultError() {
        AgentResult.Failed failed = mock(AgentResult.Failed.class);
        when(failed.reason()).thenReturn("some failure");
        assertEquals("some failure", service.formatAgentResultError(failed));

        AgentResult.LoopExceeded loopExceeded = mock(AgentResult.LoopExceeded.class);
        when(loopExceeded.maxIterations()).thenReturn(10);
        assertEquals("Agent exceeded max iterations (10)", service.formatAgentResultError(loopExceeded));

        AgentResult.AwaitingApproval awaitingApproval = mock(AgentResult.AwaitingApproval.class);
        when(awaitingApproval.toolName()).thenReturn("sandbox_exec");
        assertEquals(
                "Agent requires human approval for tool: sandbox_exec",
                service.formatAgentResultError(awaitingApproval));

        AgentResult.AwaitingRetry awaitingRetry = mock(AgentResult.AwaitingRetry.class);
        when(awaitingRetry.retryAfterMs()).thenReturn(5000);
        assertEquals("Agent hit rate limit, retry after 5s", service.formatAgentResultError(awaitingRetry));
    }

    @Test
    void attemptEscalation_secondaryEscalationSuccess() {
        AgentResult.Finished firstResult =
                new AgentResult.Finished("I cannot solve this - insufficient information", List.of(), 3, 3);
        AgentResult.Finished secondResult =
                new AgentResult.Finished("Final answer after secondary escalation", List.of(), 4, 4);

        when(agentLoop.run(anyString(), eq("debugger"), anyString(), eq(reasoningEngine), anyInt()))
                .thenReturn(firstResult);
        when(agentLoop.run(anyString(), eq("researcher"), anyString(), eq(reasoningEngine), anyInt()))
                .thenReturn(secondResult);

        var res = service.attemptEscalation(
                "sess-1",
                "usr-1",
                "chat-1",
                "coder",
                "debugger",
                "syntax error",
                "fix compilation",
                null,
                null,
                "cli",
                "cli");
        assertTrue(res.success());
        assertEquals("researcher", res.targetPersona());
        assertEquals("Final answer after secondary escalation", res.result());
    }

    // ── Task #8: cover previously-missed reachable branches ──

    @Test
    void determineEscalationTarget_returnsNullWhenNothingMatches() {
        assertNull(service.determineEscalationTarget("optimizer", "random error text", "random goal text"));
    }

    @Test
    void determineEscalationTarget_coderArchitecturePattern_toArchitect() {
        assertEquals(
                "architect", service.determineEscalationTarget("coder", "", "design the architecture and trade-off"));
    }

    @Test
    void determineEscalationTarget_coderNoPattern_returnsNull() {
        // coder with no sandbox / unknown-api / architecture pattern -> escalateFromCoder returns null (line 220)
        assertNull(service.determineEscalationTarget(
                AgentRoleEnum.CODER.toScopedName(), "a completely unrelated message", "write a unit test"));
    }

    @Test
    void determineEscalationTarget_debuggerNoPattern_returnsNull() {
        assertNull(service.determineEscalationTarget("debugger", "nothing relevant here", "plain goal"));
    }

    @Test
    void determineEscalationTarget_architectNoPattern_returnsNull() {
        assertNull(service.determineEscalationTarget("architect", "err", "design a system"));
    }

    @Test
    void determineEscalationTarget_researcherNoPattern_returnsNull() {
        assertNull(service.determineEscalationTarget("researcher", "err", "analyze the data"));
    }

    @Test
    void determineEscalationTarget_eachSandboxPhraseEscalatesToDebugger() {
        for (String phrase : List.of(
                "compile error in module",
                "syntax error on line 1",
                "classnotfound exception",
                "nosuchmethod error",
                "nullpointerexception thrown",
                "arrayindexoutofbounds failure",
                "classcastexception occurred",
                "illegalargument supplied",
                "fatal error: in Main.java")) {
            assertEquals("debugger", service.determineEscalationTarget("unknown_role", phrase, null), phrase);
        }
    }

    @Test
    void attemptEscalation_delegationThrows_wrappedAsFailure() {
        when(agentLoop.run(anyString(), eq("researcher"), anyString(), eq(reasoningEngine), anyInt()))
                .thenThrow(new RuntimeException("loop exploded"));
        var res = service.attemptEscalation(
                "sess-1", "usr-1", "chat-1", "coder", "researcher", "err", "goal", null, null, "cli", "cli");
        assertFalse(res.success());
        assertTrue(res.errorIfFailed().contains("Escalation delegation failed"));
    }

    @Test
    void attemptEscalation_nonFinishedResult_formatsError() {
        when(agentLoop.run(anyString(), eq("researcher"), anyString(), eq(reasoningEngine), anyInt()))
                .thenReturn(AgentResult.failed("boom", AgentResult.FailureCategory.SYSTEM_ERROR, List.of()));
        var res = service.attemptEscalation(
                "sess-1", "usr-1", "chat-1", "coder", "researcher", "err", "goal", null, null, "cli", "cli");
        assertFalse(res.success());
        assertEquals("boom", res.errorIfFailed());
    }

    @Test
    void buildEscalationGoal_includesRelevantCodeBlock() {
        String goal = service.buildEscalationGoal(
                "coder", "researcher", "cannot find symbol", "use List", "int x=1;", "java");
        assertTrue(goal.contains("## RELEVANT CODE:"));
        assertTrue(goal.contains("java"));
        assertTrue(goal.contains("int x=1;"));

        String goalNoLang = service.buildEscalationGoal("coder", "researcher", "err", "goal", "body", null);
        assertTrue(goalNoLang.contains("## RELEVANT CODE:"));
        assertTrue(goalNoLang.contains("text"));
    }

    @Test
    void buildEscalationGoal_displaysEveryPersonaName() {
        service.buildEscalationGoal("architect", "coordinator", "e", "g", null, null);
        service.buildEscalationGoal("synthesizer", "optimizer", "e", "g", null, null);
        service.buildEscalationGoal("sentinel", "healer", "e", "g", null, null);
        service.buildEscalationGoal("protector", "purifier", "e", "g", null, null);
        service.buildEscalationGoal("nurturer", "totally_unknown_role", "e", "g", null, null);
    }

    @Test
    void buildEscalationGoal_truncatesLongErrorOutput() {
        String longError = "x".repeat(4100);
        String goal = service.buildEscalationGoal("coder", "researcher", longError, "goal", null, null);
        assertTrue(goal.contains("[truncated]"));
    }

    @Test
    void formatAgentResultError_defaultBranchForUnknownType() {
        var finished = new AgentResult.Finished("answer", List.of(), 1, 1);
        assertTrue(service.formatAgentResultError(finished).contains("Unknown agent result"));
    }
}
