package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.*;

import com.amsha.core.message.UserMessages;

import org.junit.jupiter.api.Test;

class AgentErrorMessageUtilTest {

    @Test
    void testSafeMessage() {
        // 1. Nominal case
        Exception e1 = new Exception("Hello error");
        assertEquals("Hello error", AgentErrorMessageUtil.safeMessage(e1));

        // 2. Null message case (common for NPE)
        NullPointerException e2 = new NullPointerException();
        assertEquals("NullPointerException", AgentErrorMessageUtil.safeMessage(e2));

        // 3. Null throwable case
        assertEquals("Unknown Error", AgentErrorMessageUtil.safeMessage(null));
    }

    @Test
    void testSanitizeErrorMessage() {
        // Verify we didn't break existing logic
        assertEquals(UserMessages.TASK_FAILED, AgentErrorMessageUtil.sanitizeErrorMessage(null));
        assertEquals("Hello", AgentErrorMessageUtil.sanitizeErrorMessage("Hello"));

        // Suppress JVM internals
        String npe = "Cannot invoke \"String.length()\" because \"s\" is null";
        assertEquals(UserMessages.AI_UNEXPECTED_RESPONSE, AgentErrorMessageUtil.sanitizeErrorMessage(npe));
    }
}
