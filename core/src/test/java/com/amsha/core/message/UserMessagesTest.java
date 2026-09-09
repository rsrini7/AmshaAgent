package com.amsha.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;

class UserMessagesTest {

    @Test
    void testDelegationPausedWithoutParams() {
        String msg = UserMessages.delegationPaused("core:researcher", "sandbox_exec");
        assertTrue(msg.contains(UserMessages.DELEGATION_PAUSED_MARKER));
        assertTrue(msg.contains("tool 'sandbox_exec' needs human approval"));
        assertEquals("sandbox_exec", UserMessages.extractPausedTool(msg));
        assertEquals("{}", UserMessages.extractPausedParamsJson(msg));
    }

    @Test
    void testDelegationPausedWithEmptyOrBlankParamsJson() {
        String msg1 = UserMessages.delegationPaused("core:researcher", "sandbox_exec", null);
        assertTrue(msg1.contains("tool 'sandbox_exec' needs human approval"));
        assertEquals("{}", UserMessages.extractPausedParamsJson(msg1));

        String msg2 = UserMessages.delegationPaused("core:researcher", "sandbox_exec", "   ");
        assertTrue(msg2.contains("tool 'sandbox_exec' needs human approval"));
        assertEquals("{}", UserMessages.extractPausedParamsJson(msg2));

        String msg3 = UserMessages.delegationPaused("core:researcher", "sandbox_exec", "{}");
        assertTrue(msg3.contains("tool 'sandbox_exec' needs human approval"));
        assertEquals("{}", UserMessages.extractPausedParamsJson(msg3));
    }

    @Test
    void testDelegationPausedWithValidParamsJson() {
        String paramsJson = "{\"url\":\"https://example.com/test\"}";
        String msg = UserMessages.delegationPaused("core:researcher", "web_fetch", paramsJson);

        assertTrue(msg.contains(UserMessages.DELEGATION_PAUSED_MARKER));
        assertTrue(msg.contains("tool 'web_fetch' needs human approval"));
        assertFalse(msg.contains(paramsJson));
        assertFalse(UserMessages.userSafePortion(msg).contains(UserMessages.DELEGATION_PARAMS_BASE64_MARKER));
        assertEquals("web_fetch", UserMessages.extractPausedTool(msg));
        assertEquals(paramsJson, UserMessages.extractPausedParamsJson(msg));
    }

    @Test
    void testDelegationPausedWithPhraseInParameterPreservesJson() {
        String paramsJson = "{\"url\":\"https://example.com/needs human approval\"}";
        String msg = UserMessages.delegationPaused("core:researcher", "web_fetch", paramsJson);

        assertFalse(msg.contains(paramsJson));
        assertEquals(paramsJson, UserMessages.extractPausedParamsJson(msg));
    }

    @Test
    void testInvalidEncodedDelegationParametersFailClosed() {
        String message = "tool 'web_fetch' needs human approval\n\nCRITICAL DIRECTIVE: "
                + UserMessages.DELEGATION_PARAMS_BASE64_MARKER + "%%%";

        assertNull(UserMessages.extractPausedParamsJson(message));
    }

    @Test
    void testExtractPausedFallback() {
        assertEquals(ToolCapability.DELEGATE_TO_AGENT.toolName(), UserMessages.extractPausedTool(null));
        assertEquals(
                ToolCapability.DELEGATE_TO_AGENT.toolName(), UserMessages.extractPausedTool("some random message"));
        assertEquals("{}", UserMessages.extractPausedParamsJson(null));
        assertEquals("{}", UserMessages.extractPausedParamsJson("some random message"));
    }

    @Test
    void testStripInternalDirectiveTail() {
        String msg = UserMessages.delegationPaused("brahma", "sandbox_exec");
        String userSafe = UserMessages.stripInternalDirectiveTail(msg);
        assertFalse(userSafe.contains(UserMessages.CRITICAL_DIRECTIVE_MARKER));
        assertTrue(userSafe.contains("tool 'sandbox_exec' needs human approval"));
    }
}
