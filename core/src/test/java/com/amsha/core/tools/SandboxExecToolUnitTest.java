package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.sandbox.ProcessIsolationExecutor;
import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    void testSandboxCommandUnsupportedLanguage() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        assertThrows(
                IllegalArgumentException.class,
                () -> tool.sandboxCommand(ctx, Map.of("code", "print(1)", "language", "brainfuck")));
    }
}
