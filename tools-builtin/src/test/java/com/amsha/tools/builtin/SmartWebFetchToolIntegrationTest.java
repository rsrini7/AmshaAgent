package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.amsha.core.audit.AuditService;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmartWebFetchToolIntegrationTest {

    private SmartWebFetchTool tool;
    private ToolExecutionContext mockCtx;

    @BeforeEach
    void setUp() {
        tool = new SmartWebFetchTool();
        tool.auditService = mock(AuditService.class);
        mockCtx = mock(ToolExecutionContext.class);
        when(mockCtx.userId()).thenReturn("user-1");
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
