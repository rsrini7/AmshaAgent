package com.amsha.tool.playwright;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Map;
import java.util.Set;

import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tools.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlaywrightMcpHelperTest {

    @Mock
    BeanManager beanManager;

    PlaywrightMcpHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PlaywrightMcpHelper();
        helper.beanManager = beanManager;
        helper.objectMapper = new ObjectMapper();
        helper.mcpEnabled = false;
        helper.mcpHeadless = true;
        helper.mcpCommand = "npx";
        helper.mcpArgsRaw = "@playwright/mcp";
    }

    @Test
    void testNotAvailableWhenDisabled() {
        assertFalse(helper.isAvailable());
        helper.ensureConnected();
        assertFalse(helper.isAvailable());
    }

    @Test
    void testEnsureConnectedWithNoService() {
        helper.mcpEnabled = true;
        when(beanManager.getBeans(Object.class)).thenReturn(Set.of());

        helper.ensureConnected();
        assertFalse(helper.isAvailable());
    }

    @Test
    void testInvokeNoServiceThrows() {
        when(beanManager.getBeans(Object.class)).thenReturn(Set.of());
        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "COORDINATOR", AutonomyLevel.FULL, "t1", "WEBSOCKET", "p1", "g", null, null, null);

        assertThrows(RuntimeException.class, () -> helper.invoke(ctx, Map.of("action", "extract_text")));
    }
}
