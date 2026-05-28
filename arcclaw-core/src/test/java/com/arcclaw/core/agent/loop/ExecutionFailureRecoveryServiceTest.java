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
class ExecutionFailureRecoveryServiceTest {

    private ExecutionFailureRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionFailureRecoveryService();
        service.autoHealEnabled = true;
        service.maxAutoHealAttempts = 2;
    }

    @Test
    void shouldAttemptRecovery_enabled_coder_sandboxExec_failure() {
        ToolResponse response = ToolResponse.failure(1, "syntax error");
        assertTrue(service.shouldAttemptRecovery("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptRecovery_disabled_whenFeatureOff() {
        service.autoHealEnabled = false;
        ToolResponse response = ToolResponse.failure(1, "syntax error");
        assertFalse(service.shouldAttemptRecovery("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptRecovery_enabled_forAllRoles_sandboxExec() {
        ToolResponse response = ToolResponse.failure(1, "syntax error");
        assertTrue(service.shouldAttemptRecovery("coordinator", ToolCapability.SANDBOX_EXEC.toolName(), response));
        assertTrue(service.shouldAttemptRecovery("researcher", ToolCapability.SANDBOX_EXEC.toolName(), response));
        assertTrue(service.shouldAttemptRecovery("debugger", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptRecovery_disabled_forNonSandboxTool() {
        ToolResponse response = ToolResponse.failure(1, "error");
        assertFalse(service.shouldAttemptRecovery("coder", ToolCapability.DELEGATE_TO_AGENT.toolName(), response));
        assertFalse(service.shouldAttemptRecovery("coder", ToolCapability.GITHUB.toolName(), response));
    }

    @Test
    void shouldAttemptRecovery_disabled_forInfraFailure() {
        ToolResponse response = ToolResponse.infraFailure("podman offline");
        assertFalse(service.shouldAttemptRecovery("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void shouldAttemptRecovery_disabled_forSuccess() {
        ToolResponse response = ToolResponse.success("output");
        assertFalse(service.shouldAttemptRecovery("coder", ToolCapability.SANDBOX_EXEC.toolName(), response));
    }

    @Test
    void extractCodeFromResponse_fencedCodeBlock() {
        String response =
                "Here is the fixed code:\n```go\npackage main\n\nfunc main() {\n    fmt.Println(\"hello\")\n}\n```";
        String code = service.extractCodeFromResponse(response);
        assertNotNull(code);
        assertEquals("package main\n\nfunc main() {\n    fmt.Println(\"hello\")\n}", code);
    }

    @Test
    void extractCodeFromResponse_fencedCodeBlockWithLanguage() {
        String response =
                "```java\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println(\"hi\");\n    }\n}\n```";
        String code = service.extractCodeFromResponse(response);
        assertNotNull(code);
        assertTrue(code.contains("public class Main"));
    }

    @Test
    void extractCodeFromResponse_inlineBacktick() {
        String response = "The fix is: `print(\"hello\")`";
        String code = service.extractCodeFromResponse(response);
        assertEquals("print(\"hello\")", code);
    }

    @Test
    void extractCodeFromResponse_nullReturnsNull() {
        assertNull(service.extractCodeFromResponse(null));
        assertNull(service.extractCodeFromResponse(""));
        assertNull(service.extractCodeFromResponse("   "));
    }

    @Test
    void extractCodeFromResponse_conversationalTextReturnsNull() {
        String response = "Here is the fix for your code. The issue was a missing semicolon.";
        assertNull(service.extractCodeFromResponse(response));
    }

    @Test
    void extractCodeFromResponse_multipleCodeBlocks_returnsFirst() {
        String response = "First block:\n```python\nprint('a')\n```\n\nSecond block:\n```python\nprint('b')\n```";
        String code = service.extractCodeFromResponse(response);
        assertNotNull(code);
        assertTrue(code.contains("print('a')"));
    }

    @Test
    void buildHealGoal_containsErrorAndCode() {
        String goal = service.buildHealGoal("print('hello'", "python", "SyntaxError: unexpected EOF", 1);
        assertTrue(goal.contains("AUTO-HEAL MODE"));
        assertTrue(goal.contains("python"));
        assertTrue(goal.contains("SyntaxError: unexpected EOF"));
        assertTrue(goal.contains("print('hello'"));
        assertTrue(goal.contains("attempt 1"));
    }

    @Test
    void buildHealGoal_truncatesLongErrors() {
        String longError = "x".repeat(5000);
        String goal = service.buildHealGoal("code", "java", longError, 1);
        assertTrue(goal.contains("[truncated]"));
        assertFalse(goal.contains("x".repeat(4100)));
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
        AgentResult result =
                AgentResult.awaitingApproval(ToolCapability.SANDBOX_EXEC.toolName(), "null", "sess1", null);
        String msg = service.formatAgentResultError(result);
        assertTrue(msg.contains("requires human approval"));
    }

    @Test
    void recoveryResult_successRecord() {
        ExecutionFailureRecoveryService.RecoveryResult result =
                ExecutionFailureRecoveryService.RecoveryResult.success("fixed code", 2);
        assertTrue(result.success());
        assertEquals("fixed code", result.fixedCode());
        assertEquals(2, result.attemptsUsed());
        assertNull(result.errorIfFailed());
    }

    @Test
    void recoveryResult_failureRecord() {
        ExecutionFailureRecoveryService.RecoveryResult result =
                ExecutionFailureRecoveryService.RecoveryResult.failure("could not fix");
        assertFalse(result.success());
        assertNull(result.fixedCode());
        assertEquals("could not fix", result.errorIfFailed());
    }
}
