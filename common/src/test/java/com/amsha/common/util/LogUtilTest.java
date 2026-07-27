package com.amsha.common.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LogUtilTest {

    @Test
    void rootMessageReturnsRootCauseMessage() {
        Exception root = new RuntimeException("root cause");
        Exception middle = new RuntimeException("middle", root);
        Exception top = new RuntimeException("top", middle);
        assertTrue(LogUtil.rootMessage(top).contains("root cause"));
    }

    @Test
    void rootMessageFallsBackToClassName() {
        Exception e = new RuntimeException();
        assertTrue(LogUtil.rootMessage(e).contains("RuntimeException"));
    }

    @Test
    void rootMessageWithThrowable() {
        Throwable t = new Throwable("deep cause");
        assertEquals("Throwable: deep cause", LogUtil.rootMessage(t));
    }

    @Test
    void trimmedStackRespectsMaxLines() {
        Exception e = new RuntimeException("test");
        String stack = LogUtil.trimmedStack(e, 3);
        assertEquals(3, stack.lines().count());
    }

    @Test
    void trimmedStackDefaultIs10Lines() {
        Exception e = new RuntimeException("test");
        String stack = LogUtil.trimmedStack(e);
        assertEquals(10, stack.lines().count());
    }

    @Test
    void trimmedStackWithThrowableOverloads() {
        Throwable t = new Throwable("test");
        assertEquals(10, LogUtil.trimmedStack(t).lines().count());
        assertEquals(5, LogUtil.trimmedStack(t, 5).lines().count());
    }

    @Test
    void ctxWithSessionAndRole() {
        String result = LogUtil.ctx("sess-1", "user");
        assertEquals("[session=sess-1 role=user]", result);
    }

    @Test
    void ctxWithSessionRoleAndTool() {
        String result = LogUtil.ctx("sess-1", "user", "search");
        assertEquals("[session=sess-1 role=user tool=search]", result);
    }
}
