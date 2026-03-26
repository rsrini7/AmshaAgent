package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.sandbox.ProcessIsolationExecutor;
import com.arcclaw.core.sandbox.ToolExecutionException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@QuarkusTest
class SandboxExecToolTest {

    @Inject
    SandboxExecTool tool;

    @InjectMock
    ProcessIsolationExecutor mockIsolationExecutor;

    private static final String TRUNCATION_SUFFIX = "[output truncated at 8192 chars]";

    @Test
    void python_language_builds_correct_command() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "print('hello')", "language", "python");
            tool.execute(params);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<String>> cmdCaptor = ArgumentCaptor.forClass(List.class);
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), cmdCaptor.capture(), any());
            
            List<String> cmd = cmdCaptor.getValue();
            assertEquals("python3", cmd.get(0));
            assertEquals("-c", cmd.get(1));
            assertEquals("print('hello')", cmd.get(2));
            return null;
        });
    }

    @Test
    void bash_language_builds_correct_command() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "ls -la", "language", "bash");
            tool.execute(params);

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
            tool.execute(params);

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
    void unknown_language_returns_failure_without_calling_executor() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            Map<String, Object> params = Map.of("code", "puts 'hi'", "language", "ruby");
            ToolResponse r = tool.execute(params);
            
            assertFalse(r.success());
            assertTrue(r.output().contains("Unsupported language"));
            verifyNoInteractions(mockIsolationExecutor);
            return null;
        });
    }

    @Test
    void output_over_8192_chars_is_truncated() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            String bigOutput = "A".repeat(10_000);
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenReturn(bigOutput);
            
            Map<String, Object> params = Map.of("code", "dummy", "language", "python");
            ToolResponse r = tool.execute(params);
            
            assertEquals(8192 + TRUNCATION_SUFFIX.length(), r.output().length());
            assertTrue(r.output().endsWith(TRUNCATION_SUFFIX));
            return null;
        });
    }

    @Test
    void tool_execution_exception_returns_failure_not_thrown() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenThrow(new ToolExecutionException("exit 1", 1));
            
            Map<String, Object> params = Map.of("code", "exit 1", "language", "bash");
            ToolResponse r = tool.execute(params);
            
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
            ToolResponse r = tool.execute(params);
            
            assertFalse(r.success());
            assertTrue(r.output().contains("timed out"));
            return null;
        });
    }

    @Test
    void budget_exceeded_exception_propagates() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            when(mockIsolationExecutor.executeSandboxed(any(), any(), any(), any()))
                    .thenThrow(new BudgetExceededException("over limit"));
            
            Map<String, Object> params = Map.of("code", "x", "language", "python");
            assertThrows(BudgetExceededException.class, () -> tool.execute(params));
            return null;
        });
    }

    @Test
    void capability_returns_sandbox_exec() {
        assertEquals(ToolCapability.SANDBOX_EXEC, tool.capability());
    }

    @Test
    void empty_secrets_map_passed_when_params_has_no_secrets_field() throws Exception {
        WorkspaceContext.run("test-session", "user1", "chat1", AutonomyLevel.FULL, "coder", () -> {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> secretsCaptor = ArgumentCaptor.forClass(Map.class);
            Map<String, Object> params = Map.of("code", "x", "language", "python");
            tool.execute(params);
            
            verify(mockIsolationExecutor).executeSandboxed(any(), any(), any(), secretsCaptor.capture());
            assertTrue(secretsCaptor.getValue().isEmpty());
            return null;
        });
    }

    @Test
    void coder_role_has_sandbox_exec_capability() {
        assertTrue(AgentRoleEnum.CODER.definition().capabilities().contains(ToolCapability.SANDBOX_EXEC));
    }

    @Test
    void debugger_role_has_sandbox_exec_capability() {
        assertTrue(AgentRoleEnum.DEBUGGER.definition().capabilities().contains(ToolCapability.SANDBOX_EXEC));
    }

    @Test
    void coordinator_does_not_have_sandbox_exec_capability() {
        assertFalse(AgentRoleEnum.COORDINATOR.definition().capabilities().contains(ToolCapability.SANDBOX_EXEC));
    }
}
