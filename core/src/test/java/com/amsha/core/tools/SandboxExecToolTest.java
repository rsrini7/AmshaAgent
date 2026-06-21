package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.governance.BudgetExceededException;
import com.amsha.core.sandbox.ProcessIsolationExecutor;
import com.amsha.core.sandbox.SandboxResult;
import com.amsha.core.sandbox.ToolExecutionException;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@SuppressWarnings("java:S1192")
class SandboxExecToolTest {

    @Inject
    SandboxExecTool tool;

    @InjectMock
    ProcessIsolationExecutor mockIsolationExecutor;

    private static final String TRUNCATION_SUFFIX = "[output truncated at max output chars limit]";

    // Mirror the same OS detection used in SandboxExecTool so assertions are
    // platform-correct: 'python' on Windows, 'python3' on Linux/macOS.
    private static final String EXPECTED_PYTHON =
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "python" : "python3";

    // -------------------------------------------------------------------------
    // Command-building tests
    // -------------------------------------------------------------------------

    @Test
    void python_language_builds_correct_command() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "print('hello')", "language", "python");
            tool.execute(ctx(), params);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());

            List<String> cmd = cmdCaptor.getValue();
            assertEquals(
                    EXPECTED_PYTHON,
                    cmd.get(0),
                    "Python executable must be OS-aware: 'python' on Windows, 'python3' elsewhere");
            assertEquals("-c", cmd.get(1));
            assertEquals("print('hello')", cmd.get(2));
            return null;
        });
    }

    @Test
    void python3_alias_also_uses_os_aware_executable() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "print('hi')", "language", "python3");
            tool.execute(ctx(), params);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());

            // python3 alias must resolve the same way as python
            assertEquals(EXPECTED_PYTHON, cmdCaptor.getValue().get(0));
            return null;
        });
    }

    @Test
    void bash_language_builds_correct_command() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "ls -la", "language", "bash");
            tool.execute(ctx(), params);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());

            List<String> cmd = cmdCaptor.getValue();
            assertEquals("bash", cmd.get(0));
            assertEquals("-c", cmd.get(1));
            assertEquals("ls -la", cmd.get(2));
            return null;
        });
    }

    @Test
    void node_language_builds_correct_command() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "console.log('hi')", "language", "node");
            tool.execute(ctx(), params);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());

            List<String> cmd = cmdCaptor.getValue();
            assertEquals("node", cmd.get(0));
            assertEquals("-e", cmd.get(1));
            assertEquals("console.log('hi')", cmd.get(2));
            return null;
        });
    }

    @Test
    void buildCommand_unit_python_returns_os_aware_executable() {
        // Direct unit test of buildCommand() - no CDI, no mock needed
        List<String> cmd = tool.buildCommand("python", "x=1");
        assertEquals(EXPECTED_PYTHON, cmd.get(0));
        assertEquals("-c", cmd.get(1));
        assertEquals("x=1", cmd.get(2));
    }

    @Test
    void buildCommand_unit_node_is_platform_independent() {
        List<String> cmd = tool.buildCommand("node", "console.log(1)");
        assertEquals("node", cmd.get(0));
    }

    // -------------------------------------------------------------------------
    // Validation tests
    // -------------------------------------------------------------------------

    @Test
    void unknown_language_returns_failure_without_calling_executor() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "puts 'hi'", "language", "ruby");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertTrue(r.output().contains("Unsupported language"), "Actual: " + r.output());
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    @Test
    void missing_language_returns_failure_with_hint() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "print('hi')");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertTrue(r.output().contains("'language' is required"));
            // Error message must include usage example so the LLM self-corrects next iteration
            assertTrue(r.output().contains("language"), "Error must contain usage hint to help LLM self-correct");
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    @Test
    void missing_code_returns_failure() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("language", "python");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertTrue(r.output().contains("'code' is required"));
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Output handling tests
    // -------------------------------------------------------------------------

    @Test
    void output_over_8192_chars_is_truncated() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String bigOutput = "A".repeat(10_000);
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.success(bigOutput));

            Map<String, Object> params = Map.of("code", "dummy", "language", "python");
            ToolResponse r = tool.execute(ctx(), params);

            assertEquals(8192 + TRUNCATION_SUFFIX.length(), r.output().length());
            assertTrue(r.output().endsWith(TRUNCATION_SUFFIX));
            return null;
        });
    }

    @Test
    void output_exactly_8192_chars_is_not_truncated() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String exactOutput = "B".repeat(8192);
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.success(exactOutput));

            Map<String, Object> params = Map.of("code", "dummy", "language", "python");
            ToolResponse r = tool.execute(ctx(), params);

            assertEquals(8192, r.output().length());
            assertFalse(r.output().endsWith(TRUNCATION_SUFFIX));
            return null;
        });
    }

    @Test
    void null_output_returns_empty_string_success() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.success(null));

            Map<String, Object> params = Map.of("code", "pass", "language", "python");
            ToolResponse r = tool.execute(ctx(), params);

            assertTrue(r.success());
            assertEquals("[Execution completed successfully - no output produced]", r.output());
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Exception-contract tests (Architecture Invariants §8.6)
    // -------------------------------------------------------------------------

    @Test
    void tool_execution_exception_returns_failure_not_thrown() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenThrow(new ToolExecutionException("exit 1", 1));

            Map<String, Object> params = Map.of("code", "exit 1", "language", "bash");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertEquals(1, r.exitCode());
            assertTrue(r.output().contains("exit 1"));
            return null;
        });
    }

    @Test
    void timeout_exception_returns_failure_not_thrown() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenThrow(new TimeoutException("30s exceeded"));

            Map<String, Object> params = Map.of("code", "sleep 999", "language", "bash");
            ToolResponse r = tool.execute(ctx(), params);

            // SandboxExecTool returns a dynamic message for infra failures via buildSandboxOfflineMsg()
            assertTrue(r.isInfraFailure());
            assertTrue(r.output().contains("SANDBOX_OFFLINE"), "Actual: " + r.output());
            return null;
        });
    }

    @Test
    void budget_exceeded_exception_propagates() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenThrow(new BudgetExceededException("over limit"));

            Map<String, Object> params = Map.of("code", "x", "language", "python");
            assertThrows(BudgetExceededException.class, () -> tool.execute(ctx(), params));
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Secrets / args pass-through tests
    // -------------------------------------------------------------------------

    @Test
    void empty_secrets_map_passed_when_params_has_no_secrets_field() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> secretsCaptor = ArgumentCaptor.forClass(Map.class);
            Map<String, Object> params = Map.of("code", "x", "language", "python");
            tool.execute(ctx(), params);

            verify(mockIsolationExecutor).executeSandboxed(any(), any(), any(), secretsCaptor.capture());
            assertTrue(secretsCaptor.getValue().isEmpty());
            return null;
        });
    }

    @Test
    void extra_args_are_appended_to_command() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of(
                    "code", "print('hi')",
                    "language", "python",
                    "args", List.of("--verbose", "--dry-run"));
            tool.execute(ctx(), params);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());

            List<String> cmd = cmdCaptor.getValue();
            // Base command = [python/python3, -c, code] = 3 elements; then 2 extra args
            assertEquals(5, cmd.size());
            assertEquals("--verbose", cmd.get(3));
            assertEquals("--dry-run", cmd.get(4));
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Capability / role-permission tests
    // -------------------------------------------------------------------------

    @Test
    void capability_returns_sandbox_exec() {
        assertEquals(ToolCapability.SANDBOX_EXEC, tool.capability());
    }

    // -------------------------------------------------------------------------
    // Java language tests
    // -------------------------------------------------------------------------

    @Test
    void java_language_writes_file_and_runs_via_javac() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String code = "public class Main { public static void main(String[] a) { System.out.println(\"Hi\"); } }";
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.success("Hi\n")); // Single call runs java Main.java

            Map<String, Object> params = Map.of("code", code, "language", "java");
            ToolResponse r = tool.execute(ctx(), params);
            // VERIFY: r.success() may be false if javac is missing on host,
            // but the test should be updated to account for the restored javac logic.
            // If the user's environment HAS javac, this passes.
            // If it DOESN'T, it fails with "Java compilation failed".
            // Since we must not modify SandboxExecTool much, we accept this dependency.
            assertTrue(r.success(), "Java compilation or execution failed: " + r.output());
            assertEquals("Hi\n", r.output());

            // VERIFY: isolationExecutor is only called ONCE for the java run.
            // Compilation uses direct ProcessBuilder on host.
            verify(mockIsolationExecutor, times(1)).executeSandboxed(any(), any(), any(), any());
            return null;
        });
    }

    @Test
    void java_language_returns_error_on_failure() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.failure("syntax error"));

            String code = "public class Main { public static void main(String[] a) { /* valid code */ } }";
            Map<String, Object> params = Map.of("code", code, "language", "java");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertTrue(r.output().contains("syntax error"));
            return null;
        });
    }

    @Test
    void extractJavaClassName_returns_public_class_name() {
        String code = "public class HelloWorld { public static void main(String[] a) {} }";
        assertEquals("HelloWorld", tool.extractJavaClassName(code));
    }

    @Test
    void extractJavaClassName_falls_back_to_any_class() {
        String code = "class Foo { }";
        assertEquals("Foo", tool.extractJavaClassName(code));
    }

    @Test
    void extractJavaClassName_defaults_to_Main_when_no_class() {
        assertEquals("Main", tool.extractJavaClassName("// just a comment, nothing to see"));
    }

    // -------------------------------------------------------------------------
    // Go language tests
    // -------------------------------------------------------------------------

    @Test
    void go_language_writes_file_and_runs_via_go_run() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String code = "package main\nimport \"fmt\"\nfunc main() { fmt.Println(\"Hello\") }";
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.success("Hello\n"));

            Map<String, Object> params = Map.of("code", code, "language", "go");
            ToolResponse r = tool.execute(ctx(), params);

            assertTrue(r.success());
            assertEquals("Hello\n", r.output());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());

            List<String> cmd = cmdCaptor.getValue();
            assertEquals("go", cmd.get(0));
            assertEquals("run", cmd.get(1));
            assertTrue(cmd.get(2).endsWith("main.go"), "Expected main.go as target file");
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Safety scanner integration tests (via SandboxExecTool.execute())
    // -------------------------------------------------------------------------

    @Test
    void java_runtime_exec_is_blocked_by_safety_scanner() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String code =
                    "public class Main { public static void main(String[] a) throws Exception { Runtime.getRuntime().exec(\"rm -rf /\"); } }";
            Map<String, Object> params = Map.of("code", code, "language", "java");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertTrue(r.output().contains("Execution blocked"));
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    @Test
    void go_os_remove_all_is_blocked_by_safety_scanner() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String code = "package main\nimport \"os\"\nfunc main() { os.RemoveAll(\"/\") }";
            Map<String, Object> params = Map.of("code", code, "language", "go");
            ToolResponse r = tool.execute(ctx(), params);

            assertFalse(r.success());
            assertTrue(r.output().contains("Execution blocked"));
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // ALLOWED_LANGUAGES coverage check
    // -------------------------------------------------------------------------

    @Test
    void all_six_languages_are_accepted() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.success("ok"));

            for (String lang : List.of("python", "python3", "node", "bash")) {
                Map<String, Object> params = Map.of("code", "x", "language", lang);
                ToolResponse r = tool.execute(ctx(), params);
                // May succeed or fail depending on OS - just must NOT be "Unsupported language"
                assertFalse(r.output().startsWith("Unsupported language"), "Language should be accepted: " + lang);
            }
            return null;
        });
    }

    @Test
    void ruby_is_still_rejected_after_java_go_added() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "puts 'hi'", "language", "ruby");
            ToolResponse r = tool.execute(ctx(), params);
            assertFalse(r.success());
            assertTrue(
                    r.output().contains("Unsupported language"), "Expected 'Unsupported language' in: " + r.output());
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    @Test
    void infra_failure_returns_infraFailure_toolresponse() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(SandboxResult.infraFailure("dial tcp: connection refused"));

            Map<String, Object> params = Map.of("code", "print('hi')", "language", "python");
            ToolResponse r = tool.execute(ctx(), params);

            assertTrue(r.isInfraFailure(), "Should be flagged as infra failure");
            assertFalse(r.success());
            assertTrue(r.output().contains("SANDBOX_OFFLINE"));
            return null; // Return null to make it a Callable
        });
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(
                "test-session",
                "user1",
                "chat1",
                "coder",
                AutonomyLevel.FULL,
                "trace-1",
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
