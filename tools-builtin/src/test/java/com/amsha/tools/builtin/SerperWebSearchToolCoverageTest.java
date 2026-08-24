package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Optional;

import com.amsha.security.api.AutonomyLevel;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.ToolOperation;
import com.amsha.tool.api.IdempotencyMode;
import com.amsha.tool.api.ToolCapability;
import com.amsha.tool.api.ToolExecutionContext;
import com.amsha.tool.api.ToolResponse;

import org.junit.jupiter.api.Test;

class SerperWebSearchToolCoverageTest {

    @Test
    void testMetadataAndCapabilities() {
        SerperWebSearchTool tool = new SerperWebSearchTool();
        assertEquals(ToolCapability.WEB_SEARCH_SERPER.toolName(), tool.name());
        assertEquals(ToolCapability.WEB_SEARCH_SERPER.description(), tool.description());
        assertFalse(tool.requiresSandbox());
        assertEquals(ToolOperation.NETWORK_OUTBOUND, tool.requiredOperation());
        assertEquals(IdempotencyMode.IDEMPOTENT, tool.idempotencyMode());
        assertNotNull(tool.parametersSchema());
    }

    @Test
    void testIsEnabledLogic() {
        SerperWebSearchTool tool = new SerperWebSearchTool();
        tool.vault = mock(SecretVault.class);

        when(tool.vault.systemKeyExists("AMSHA_TOOLS_SERPER_API_KEY")).thenReturn(true);
        assertTrue(tool.isEnabled());

        when(tool.vault.systemKeyExists("AMSHA_TOOLS_SERPER_API_KEY")).thenReturn(false);
        tool.configApiKey = "none";
        assertFalse(tool.isEnabled());

        tool.configApiKey = "   ";
        assertFalse(tool.isEnabled());

        tool.configApiKey = null;
        assertFalse(tool.isEnabled());

        tool.configApiKey = "valid-api-key";
        assertTrue(tool.isEnabled());
    }

    @Test
    void testExecuteMissingQuery() {
        SerperWebSearchTool tool = new SerperWebSearchTool();
        tool.configApiKey = "key";
        tool.vault = mock(SecretVault.class);
        when(tool.vault.retrieveSystemKey(any())).thenReturn(Optional.empty());

        ToolExecutionContext ctx = new ToolExecutionContext(
                "sess-1",
                "user-1",
                "chat-1",
                "coder",
                AutonomyLevel.FULL,
                "trace-1",
                "telegram",
                "plat-1",
                null,
                null,
                null,
                null);

        ToolResponse resp = tool.execute(ctx, Map.of());
        assertNotNull(resp);
        assertFalse(resp.success());
        assertTrue(resp.output().contains("query parameter is required"));
    }
}
