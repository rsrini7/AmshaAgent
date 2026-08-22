package com.amsha.tool.playwright;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.tools.ToolExecutionContext;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class PlaywrightMcpHelperTest {

    @Test
    void testHelperInstantiationAndAvailability() {
        PlaywrightMcpHelper helper = new PlaywrightMcpHelper();
        assertNotNull(helper);
        assertFalse(helper.isAvailable());

        helper.mcpEnabled = false;
        assertFalse(helper.isAvailable());
    }

    @Test
    void testEnsureConnectedDisabled() {
        PlaywrightMcpHelper helper = new PlaywrightMcpHelper();
        helper.mcpEnabled = false;
        helper.ensureConnected();
        assertFalse(helper.isAvailable());
    }

    @Test
    void testEnsureConnectedNoBeans() {
        PlaywrightMcpHelper helper = new PlaywrightMcpHelper();
        helper.mcpEnabled = true;
        helper.mcpCommand = "npx";
        helper.mcpArgsRaw = "@playwright/mcp";
        helper.beanManager = mock(BeanManager.class);
        when(helper.beanManager.getBeans(any())).thenReturn(Set.of());

        helper.ensureConnected();
        assertFalse(helper.isAvailable());
    }

    @Test
    void testInvokeWhenNotAvailableThrows() {
        PlaywrightMcpHelper helper = new PlaywrightMcpHelper();
        helper.beanManager = mock(BeanManager.class);
        when(helper.beanManager.getBeans(any())).thenReturn(Set.of());

        ToolExecutionContext ctx = new ToolExecutionContext(
                "s1", "u1", "c1", "coder", AutonomyLevel.FULL, "t1", "telegram", "p1", null, null, null, null);

        assertThrows(RuntimeException.class, () -> helper.invoke(ctx, Map.of("action", "extract_text")));
    }

    @Test
    void testToMcpToolNameViaReflection() throws Exception {
        Method m = PlaywrightMcpHelper.class.getDeclaredMethod("toMcpToolName", String.class);
        m.setAccessible(true);

        assertEquals("browser_navigate", m.invoke(null, "navigate"));
        assertEquals("browser_navigate", m.invoke(null, "extract_text"));
        assertEquals("browser_take_screenshot", m.invoke(null, "screenshot"));
        assertEquals("browser_click", m.invoke(null, "click"));
        assertEquals("browser_type", m.invoke(null, "fill"));
        assertEquals("browser_wait_for", m.invoke(null, "wait"));
        assertEquals("browser_evaluate", m.invoke(null, "eval"));
        assertNull(m.invoke(null, "unknown_action"));
    }

    @Test
    void testParseArgsViaReflection() throws Exception {
        Method m = PlaywrightMcpHelper.class.getDeclaredMethod("parseArgs", String.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> empty1 = (List<String>) m.invoke(null, (String) null);
        assertTrue(empty1.isEmpty());

        @SuppressWarnings("unchecked")
        List<String> empty2 = (List<String>) m.invoke(null, "   ");
        assertTrue(empty2.isEmpty());

        @SuppressWarnings("unchecked")
        List<String> args1 = (List<String>) m.invoke(null, "--headless --port 8080");
        assertEquals(List.of("--headless", "--port", "8080"), args1);

        @SuppressWarnings("unchecked")
        List<String> args2 = (List<String>) m.invoke(null, "--name 'quoted arg' --flag");
        assertEquals(List.of("--name", "quoted arg", "--flag"), args2);
    }
}
