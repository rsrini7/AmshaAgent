package com.arcclaw.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;

import com.arcclaw.core.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmartWebFetchToolIntegrationTest {

    private SmartWebFetchTool tool;
    private ToolExecutionContext mockCtx;

    @BeforeEach
    void setUp() {
        tool = new SmartWebFetchTool();
        tool.auditService = mock(AuditService.class);
        tool.init();
        mockCtx = mock(ToolExecutionContext.class);
    }

    @AfterEach
    void tearDown() {
        tool.cleanup();
    }

    @Test
    void testExecuteRejectsLoopback() {
        ToolResponse response = tool.execute(mockCtx, Map.of("url", "http://127.0.0.1:80"));
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("private/internal network hosts is not allowed"));
    }

    @Test
    void testExecuteRejectsLocalhost() {
        ToolResponse response = tool.execute(mockCtx, Map.of("url", "http://localhost:8080"));
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("private/internal network hosts is not allowed"));
    }

    @Test
    void testExecuteRejectsPrivateIPRange() {
        ToolResponse response = tool.execute(mockCtx, Map.of("url", "http://10.0.0.1"));
        assertFalse(response.success());
        assertEquals(403, response.exitCode());
        assertTrue(response.output().contains("private/internal network hosts is not allowed"));
    }

    @Test
    void testExecuteRejectsInvalidScheme() {
        ToolResponse response = tool.execute(mockCtx, Map.of("url", "ftp://example.com"));
        assertFalse(response.success());
        assertEquals(400, response.exitCode());
        assertTrue(response.output().contains("Unsupported URL scheme"));
    }
}
