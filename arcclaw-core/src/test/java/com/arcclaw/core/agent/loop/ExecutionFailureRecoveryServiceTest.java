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
    void extractCodeFromResponse_inlineBacktick_returnsNull() {
        String response = "The fix is: `print(\"hello\")`";
        assertNull(service.extractCodeFromResponse(response));
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
    void extractCodeFromResponse_multipleCodeBlocks_returnsFirstLongEnough() {
        String response =
                "First block:\n```python\nprint('hello world example')\n```\n\nSecond block:\n```python\nprint('second block code')\n```";
        String code = service.extractCodeFromResponse(response);
        assertNotNull(code);
        assertTrue(code.contains("hello world example"));
    }

    @Test
    void extractCodeFromResponse_shortBlocksSkipped() {
        String response =
                "Example:\n```python\nx = 1\n```\n\nActual fix:\n```python\nprint('the real fix with enough chars')\n```";
        String code = service.extractCodeFromResponse(response);
        assertNotNull(code);
        assertTrue(code.contains("the real fix"));
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
        assertNull(result.extractedCode());
    }

    @Test
    void recoveryResult_failureRecord() {
        ExecutionFailureRecoveryService.RecoveryResult result =
                ExecutionFailureRecoveryService.RecoveryResult.failure("could not fix");
        assertFalse(result.success());
        assertNull(result.fixedCode());
        assertEquals("could not fix", result.errorIfFailed());
        assertNull(result.extractedCode());
    }

    @Test
    void recoveryResult_failureWithExtractedCode() {
        ExecutionFailureRecoveryService.RecoveryResult result =
                ExecutionFailureRecoveryService.RecoveryResult.failure("validation failed", "public class Main {}");
        assertFalse(result.success());
        assertNull(result.fixedCode());
        assertEquals("validation failed", result.errorIfFailed());
        assertEquals("public class Main {}", result.extractedCode());
    }

    // ── validateExtractedCode tests ─────────────────────────────────────

    @Test
    void validateExtractedCode_identicalCode_returnsError() {
        String original =
                "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"hi\");\n    }\n}";
        String fixed = original;
        String error = service.validateExtractedCode(fixed, original, "java");
        assertNotNull(error);
        assertTrue(error.contains("identical"));
    }

    @Test
    void validateExtractedCode_identicalCodeWithWhitespace_returnsError() {
        String original = "  public class Main { }  ";
        String fixed = "public class Main { }";
        String error = service.validateExtractedCode(fixed, original, "java");
        assertNotNull(error);
        assertTrue(error.contains("identical"));
    }

    @Test
    void validateExtractedCode_javaWithTypeDeclNoMain_returnsError() {
        String fixed =
                "public class Main {\n    public String reverse(String s) {\n        return new StringBuilder(s).reverse().toString();\n    }\n}";
        String original = "public class Main {\n    public String reverse(String s) { return s; }\n}";
        String error = service.validateExtractedCode(fixed, original, "java");
        assertNotNull(error);
        assertTrue(error.contains("main()"));
    }

    @Test
    void validateExtractedCode_javaWithMain_returnsNull() {
        String fixed =
                "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"fixed\");\n    }\n}";
        String original =
                "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"broken\"\n    }\n}";
        assertNull(service.validateExtractedCode(fixed, original, "java"));
    }

    @Test
    void validateExtractedCode_javaInstanceMain_returnsNull() {
        // Instance main method (JEP 463, JDK 21+) — no static modifier
        String fixed =
                "public class Main {\n    void main(String[] args) {\n        System.out.println(\"instance main\");\n    }\n}";
        String original = "class Main {}";
        assertNull(service.validateExtractedCode(fixed, original, "java"));
    }

    @Test
    void validateExtractedCode_javaVarargsMain_returnsNull() {
        // Varargs syntax
        String fixed =
                "public class Main {\n    static void main(String... args) {\n        System.out.println(\"varargs\");\n    }\n}";
        String original = "class Main {}";
        assertNull(service.validateExtractedCode(fixed, original, "java"));
    }

    @Test
    void validateExtractedCode_javaStaticMainNoPublic_returnsNull() {
        // static void main without public modifier
        String fixed =
                "class Main {\n    static void main(String[] args) {\n        System.out.println(\"no public\");\n    }\n}";
        String original = "class Main {}";
        assertNull(service.validateExtractedCode(fixed, original, "java"));
    }

    @Test
    void validateExtractedCode_javaNoArgsMain_returnsNull() {
        // No-args main (JEP 463, JDK 21+)
        String fixed = "public class Main {\n    void main() {\n        System.out.println(\"no args\");\n    }\n}";
        String original = "class Main {}";
        assertNull(service.validateExtractedCode(fixed, original, "java"));
    }

    @Test
    void validateExtractedCode_javaNoTypeDecl_returnsNull() {
        // Code without type declaration will be wrapped by prepareWorkspace
        String fixed = "System.out.println(\"hello world\");";
        String original = "System.out.println(\"hello\"";
        assertNull(service.validateExtractedCode(fixed, original, "java"));
    }

    @Test
    void validateExtractedCode_pythonIdentical_returnsError() {
        String code = "print('hello')";
        String error = service.validateExtractedCode(code, code, "python");
        assertNotNull(error);
        assertTrue(error.contains("identical"));
    }

    @Test
    void validateExtractedCode_pythonDifferent_returnsNull() {
        String error = service.validateExtractedCode("print('fixed')", "print('broken'", "python");
        assertNull(error);
    }

    // ── validateForExecution tests ──────────────────────────────────────

    @Test
    void validateForExecution_javaWithMain_returnsNull() {
        String code =
                "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"ok\");\n    }\n}";
        assertNull(service.validateForExecution(code, "java"));
    }

    @Test
    void validateForExecution_javaNoMain_returnsError() {
        String code = "public class Main {\n    public void run() { }\n}";
        String error = service.validateForExecution(code, "java");
        assertNotNull(error);
        assertTrue(error.contains("main()"));
    }

    @Test
    void validateForExecution_javaNoTypeDecl_returnsNull() {
        String code = "System.out.println(\"hello world\");";
        assertNull(service.validateForExecution(code, "java"));
    }

    @Test
    void validateForExecution_python_returnsNull() {
        assertNull(service.validateForExecution("print('hello')", "python"));
    }

    @Test
    void buildHealGoal_mentionsSandboxExec() {
        String goal = service.buildHealGoal("int x = 1;", "java", "error", 1);
        assertTrue(goal.contains("sandbox_exec"));
    }
}
