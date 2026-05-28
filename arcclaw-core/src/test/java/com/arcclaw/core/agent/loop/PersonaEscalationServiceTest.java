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
    void determineEscalationTarget_shiva_complexLogic_toGanesha() {
        String target = service.determineEscalationTarget("debugger", "dummy", "this recursive algorithm is complex");
        assertEquals("optimizer", target);
    }

    @Test
    void determineEscalationTarget_shiva_securityVulnerability_toKartikeya() {
        String target = service.determineEscalationTarget("debugger", "dummy", "found a security vulnerability in the code");
        assertEquals("sentinel", target);
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
    void determineEscalationTarget_sandboxCodeError_escalatesToDebugger() {
        String target = service.determineEscalationTarget("coder", "simple syntax error in line 5", null);
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_nonSandboxError_returnsNull() {
        String target = service.determineEscalationTarget("coordinator", "rate limit exceeded", null);
        assertNull(target);
    }

    @Test
    void determineEscalationTarget_nullSource_returnsNull() {
        String target = service.determineEscalationTarget(null, "error", null);
        assertNull(target);
    }

    // Ganesha (Optimizer) escalation tests
    @Test
    void determineEscalationTarget_ganesha_needsImplementation_toBrahma() {
        String target = service.determineEscalationTarget("optimizer", "dummy", "implement this solution");
        assertEquals("coder", target);
    }

    @Test
    void determineEscalationTarget_ganesha_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("optimizer", "dummy", "research the algorithm");
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_ganesha_needsDebug_toShiva() {
        String target = service.determineEscalationTarget("optimizer", "dummy", "fix bug in logic");
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_ganesha_securityIssue_toKartikeya() {
        String target = service.determineEscalationTarget("optimizer", "dummy", "this logic has a security vulnerability");
        assertEquals("sentinel", target);
    }

    // Kartikeya (Sentinel) escalation tests
    @Test
    void determineEscalationTarget_kartikeya_needsFix_toShiva() {
        String target = service.determineEscalationTarget("sentinel", "dummy", "patch the vulnerability");
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_kartikeya_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("sentinel", "dummy", "research CVE details");
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_kartikeya_cveLowercase_toSaraswati() {
        String target = service.determineEscalationTarget("sentinel", "dummy", "look up cve-2024-1234");
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_kartikeya_complexLogic_toGanesha() {
        String target = service.determineEscalationTarget("sentinel", "dummy", "this requires complex algorithm analysis");
        assertEquals("optimizer", target);
    }

    // Lakshmi (Healer) escalation tests
    @Test
    void determineEscalationTarget_lakshmi_needsFix_toShiva() {
        String target = service.determineEscalationTarget("healer", "dummy", "fix the error");
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_lakshmi_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("healer", "dummy", "research best practices");
        assertEquals("researcher", target);
    }

    @Test
    void determineEscalationTarget_lakshmi_budgetCoordination_toVishnu() {
        String target = service.determineEscalationTarget("healer", "dummy", "need budget allocation review");
        assertEquals("coordinator", target);
    }

    // Durga (Protector) escalation tests
    @Test
    void determineEscalationTarget_durga_needsFix_toShiva() {
        String target = service.determineEscalationTarget("protector", "dummy", "implement policy fix");
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_durga_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("protector", "dummy", "research compliance rules");
        assertEquals("researcher", target);
    }

    // Kali (Purifier) escalation tests
    @Test
    void determineEscalationTarget_kali_needsFix_toShiva() {
        String target = service.determineEscalationTarget("purifier", "dummy", "fix cleanup code");
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_kali_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("purifier", "dummy", "research best practices");
        assertEquals("researcher", target);
    }

    // Parvati (Nurturer) escalation tests
    @Test
    void determineEscalationTarget_parvati_needsFix_toShiva() {
        String target = service.determineEscalationTarget("nurturer", "dummy", "fix the bug");
        assertEquals("debugger", target);
    }

    @Test
    void determineEscalationTarget_parvati_needsResearch_toSaraswati() {
        String target = service.determineEscalationTarget("nurturer", "dummy", "research documentation");
        assertEquals("researcher", target);
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
