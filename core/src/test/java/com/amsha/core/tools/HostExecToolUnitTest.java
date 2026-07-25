package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.AuditService;
import com.amsha.core.sandbox.DirectExecutor;
import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HostExecToolUnitTest {

    @Mock
    AuditService auditService;

    @Mock
    HostJavaCompiler hostJavaCompiler;

    @Mock
    DirectExecutor directExecutor;

    @InjectMocks
    HostExecTool tool;

    @BeforeEach
    void setUp() {
        tool.init();
        tool.workspaceRootDir = "./target/test-workspaces";
    }

    @Test
    void testMetadataAndSchema() {
        assertEquals(ToolCapability.HOST_EXEC.toolName(), tool.name());
        assertFalse(tool.requiresSandbox());
        assertNotNull(tool.parametersSchema());
        assertEquals(List.of("code"), tool.parametersSchema().required());
    }

    @Test
    void testExecuteNullOrEmptyParams() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        ToolResponse nullRes = tool.execute(ctx, null);
        assertFalse(nullRes.success());
        assertTrue(nullRes.output().contains("Parameters are required"));

        ToolResponse emptyRes = tool.execute(ctx, Map.of());
        assertFalse(emptyRes.success());
        assertTrue(emptyRes.output().contains("'code' is required"));
    }

    @Test
    void testExecuteUnsupportedLanguage() {
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", null, null, "Goal", null, null, null, null);

        ToolResponse res = tool.execute(ctx, Map.of("code", "echo 1", "language", "unsupported_lang"));
        assertFalse(res.success());
        assertTrue(res.output().contains("language not supported"));
    }

    @Test
    void testPostProcessOutput() {
        String output = tool.postProcessOutput("hello world", Map.of("code", "echo 1"));
        assertNotNull(output);
    }
}
