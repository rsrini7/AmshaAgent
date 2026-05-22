package com.arcclaw.core.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.tools.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonaEscalationServiceTest {

    private PersonaEscalationService service;

    @BeforeEach
    void setUp() {
        service = new PersonaEscalationService();
        service.escalationEnabled = true;
        service.maxEscalationDepth = 2;
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
    void determineEscalationTarget_brahma_designPattern_toBrihaspati() {
        String target = service.determineEscalationTarget("coder", "best practice for this", null);
        assertEquals("architect", target);
    }

    @Test
    void determineEscalationTarget_shiva_obscureError_toSaraswati() {
        String target = service.determineEscalationTarget("debugger", "unknown error, unable to diagnose", null);
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_shiva_insufficientInfo_toSaraswati() {
        String target = service.determineEscalationTarget("debugger", "insufficient information to determine root cause", null);
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_shiva_fundamentalFlaw_toBrahma() {
        String target = service.determineEscalationTarget("debugger", "fundamental flaw in design, needs to be rewritten", null);
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_shiva_architectureIssue_toBrahma() {
        String target = service.determineEscalationTarget("debugger", "architecture issue requires complete rewrite", null);
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_brihaspati_needsImplementation_toBrahma() {
        String target = service.determineEscalationTarget("architect", "dummy error", "implement this design");
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_brihaspati_writeCode_toBrahma() {
        String target = service.determineEscalationTarget("architect", "dummy", "write code for this architecture");
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_brihaspati_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("architect", "dummy", "research the latest patterns");
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_saraswati_needsImplementation_toBrahma() {
        String target = service.determineEscalationTarget("researcher", "dummy", "implement this feature");
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_saraswati_writeCode_toBrahma() {
        String target = service.determineEscalationTarget("researcher", "dummy", "write code based on research");
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_noMatch_returnsNull() {
        String target = service.determineEscalationTarget("coder", "simple syntax error", null);
        assertNull(target);
    }

    @Test
    void determineEscalationTarget_nullSource_returnsNull() {
        String target = service.determineEscalationTarget(null, "error", null);
        assertNull(target);
    }

    @Test
    void buildEscalationGoal_containsSourceAndTarget() {
        String goal = service.buildEscalationGoal("coder", "researcher", "cannot find symbol", "use List", null, null);
        assertTrue(goal.contains("ESCALATION FROM Brahma (Coder)"));
        assertTrue(goal.contains("Saraswati (Researcher)"));
    }

    @Test
    void buildEscalationGoal_containsErrorOutput() {
        String goal = service.buildEscalationGoal("coder", "researcher", "package does not exist", "goal", null, null);
        assertTrue(goal.contains("package does not exist"));
    }

    @Test
    void buildEscalationGoal_containsCodeWhenProvided() {
        String goal = service.buildEscalationGoal("coder", "researcher", "error", "goal", "public class Main {}", "java");
        assertTrue(goal.contains("public class Main {}"));
        assertTrue(goal.contains("java"));
    }

    @Test
    void buildEscalationGoal_truncatesLongErrors() {
        String longError = "x".repeat(5000);
        String goal = service.buildEscalationGoal("coder", "researcher", longError, "goal", null, null);
        assertTrue(goal.contains("[truncated]"));
    }

    @Test
    void escalationResult_successRecord() {
        PersonaEscalationService.EscalationResult result =
                PersonaEscalationService.EscalationResult.success("researcher", "found docs", 1);
        assertTrue(result.success());
        assertEquals("researcher", result.targetPersona());
        assertEquals("found docs", result.result());
        assertEquals(1, result.depthUsed());
        assertNull(result.errorIfFailed());
    }

    @Test
    void escalationResult_failureRecord() {
        PersonaEscalationService.EscalationResult result =
                PersonaEscalationService.EscalationResult.failure("delegation failed");
        assertFalse(result.success());
        assertNull(result.targetPersona());
        assertNull(result.result());
        assertEquals("delegation failed", result.errorIfFailed());
    }

    @Test
    void formatAgentResultError_failed() {
        AgentResult result = AgentResult.failed("test error", AgentResult.FailureCategory.SYSTEM_ERROR, null);
        String msg = service.formatAgentResultError(result);
        assertEquals("test error", msg);
    }

    @Test
    void formatAgentResultError_loopExceeded() {
        AgentResult result = AgentResult.loopExceeded(5, null);
        String msg = service.formatAgentResultError(result);
        assertTrue(msg.contains("exceeded max iterations"));
        assertTrue(msg.contains("5"));
    }

    @Test
    void formatAgentResultError_awaitingApproval() {
        AgentResult result = AgentResult.awaitingApproval(ToolCapability.SANDBOX_EXEC.toolName(), "null", "sess1", null);
        String msg = service.formatAgentResultError(result);
        assertTrue(msg.contains("requires human approval"));
    }
}
