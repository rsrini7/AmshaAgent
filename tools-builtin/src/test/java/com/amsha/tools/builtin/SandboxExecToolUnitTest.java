package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.sandbox.ProcessIsolationExecutor;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SandboxExecToolUnitTest {

    @Mock
    ProcessIsolationExecutor isolationExecutor;

    @Mock
    HostJavaCompiler hostJavaCompiler;

    @Mock
    AuditService auditService;

    @InjectMocks
    SandboxExecTool tool;

    @BeforeEach
    void setUp() {
        tool.init();
        tool.workspaceRootDir = "./target/test-workspaces";
    }

    @Test
    void testMetadataAndSchema() {
        assertEquals(ToolCapability.SANDBOX_EXEC.toolName(), tool.name());
        assertEquals("sandbox_exec", tool.name());
        assertTrue(tool.requiresSandbox());
        assertNotNull(tool.parametersSchema());
        assertEquals(List.of("code", "language"), tool.parametersSchema().required());
    }

    @Test
    void testSandboxCommandMissingParameters() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(ctx, Map.of("code", "echo 1")));
    }

    @Test
    void testSandboxCommandLanguages() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        List<String> pyCmd = tool.sandboxCommand(ctx, Map.of("code", "print('hello')", "language", "python"));
        assertEquals("python3", pyCmd.get(0));

        List<String> bashCmd = tool.sandboxCommand(ctx, Map.of("code", "echo 123", "language", "bash"));
        assertEquals("bash", bashCmd.get(0));

        List<String> goCmd = tool.sandboxCommand(ctx, Map.of("code", "package main", "language", "go"));
        assertEquals("go", goCmd.get(0));

        List<String> nodeCmd = tool.sandboxCommand(ctx, Map.of("code", "console.log(1)", "language", "node"));
        assertEquals("node", nodeCmd.get(0));

        List<String> javaCmd = tool.sandboxCommand(ctx, Map.of("code", "public class App {}", "language", "java"));
        assertEquals("sh", javaCmd.get(0));
    }

    @Test
    void testPrepareWorkspace(@TempDir Path tempDir) throws IOException {
        // Python
        tool.prepareWorkspace(tempDir, Map.of("code", "print(1)", "language", "python"));
        assertTrue(Files.exists(tempDir.resolve("script.py")));

        // Bash
        tool.prepareWorkspace(tempDir, Map.of("code", "echo 1", "language", "bash"));
        assertTrue(Files.exists(tempDir.resolve("script.sh")));

        // Go
        tool.prepareWorkspace(tempDir, Map.of("code", "package main", "language", "go"));
        assertTrue(Files.exists(tempDir.resolve("main.go")));

        // Node
        tool.prepareWorkspace(tempDir, Map.of("code", "console.log(1)", "language", "node"));
        assertTrue(Files.exists(tempDir.resolve("script.js")));

        // Java snippet
        tool.prepareWorkspace(tempDir, Map.of("code", "System.out.println(1);", "language", "java"));
        assertTrue(Files.exists(tempDir.resolve("Main.java")));
    }
}
