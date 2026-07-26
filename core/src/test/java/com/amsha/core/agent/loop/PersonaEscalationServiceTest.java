package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningEngine;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.tools.ToolResponse;

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
}
