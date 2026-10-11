package com.amsha.common.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

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

    @Test
    void relevantStackKeepsHeaderAndApplicationFrames() {
        Exception e = new IllegalStateException("boom");
        String stack = LogUtil.relevantStack(e);
        assertTrue(stack.startsWith("java.lang.IllegalStateException: boom"));
        assertTrue(stack.contains("LogUtilTest."));
    }

    @Test
    void relevantStackDropsFrameworkAndReflectionFrames() {
        Exception e = new IllegalStateException("boom");
        String stack = LogUtil.relevantStack(e);
        assertFalse(stack.contains("org.junit."));
        assertFalse(stack.contains("org.apache.maven."));
        // Assert on the class name, not the package prefix: redaction can rewrite the prefix, so a
        // leaked plumbing frame would otherwise slip past a prefix-only assertion.
        assertFalse(stack.contains("DirectMethodHandleAccessor"));
        assertFalse(stack.contains("Method.invoke"));
        // Module-prefixed JDK frames ("java.base/java.util.ArrayList.forEach") must normalise away too.
        assertFalse(stack.contains("java.util."));
    }

    @Test
    void relevantThrowablePreservesHeaderAndRelevantFrames() {
        Exception e = new IllegalStateException("boom");
        Throwable trimmed = LogUtil.relevantThrowable(e);

        assertEquals("java.lang.IllegalStateException: boom", trimmed.toString());
        assertTrue(hasFrameFrom(trimmed, "com.amsha.common.util.LogUtilTest"));
        assertFalse(hasFrameFrom(trimmed, "org.junit."));
        assertFalse(hasFrameFrom(trimmed, "jdk.internal.reflect."));
        assertFalse(hasFrameFrom(trimmed, "java.util."));
    }

    @Test
    void relevantThrowablePreservesCauseChain() {
        Exception e = new IllegalStateException("outer", new IllegalArgumentException("inner"));
        Throwable trimmed = LogUtil.relevantThrowable(e);

        assertNotNull(trimmed.getCause());
        assertEquals(
                "java.lang.IllegalArgumentException: inner", trimmed.getCause().toString());
    }

    @Test
    void relevantThrowableHandlesNull() {
        assertNull(LogUtil.relevantThrowable(null));
    }

    private static boolean hasFrameFrom(Throwable t, String classNamePrefix) {
        return Arrays.stream(t.getStackTrace()).anyMatch(f -> f.getClassName().startsWith(classNamePrefix));
    }

    @Test
    void relevantStackKeepsCauseChainHeader() {
        Exception e = new IllegalStateException("outer", new IllegalArgumentException("inner"));
        String stack = LogUtil.relevantStack(e);
        assertTrue(stack.contains("java.lang.IllegalStateException: outer"));
        assertTrue(stack.contains("Caused by: java.lang.IllegalArgumentException: inner"));
    }

    @Test
    void relevantStackCapsLines() {
        Exception e = new IllegalStateException("boom");
        assertEquals(1, LogUtil.relevantStack((Throwable) e, 1).lines().count());
    }

    @Test
    void relevantStackHandlesNull() {
        assertEquals("", LogUtil.relevantStack((Throwable) null));
    }

    @Test
    void relevantStackRedactsTelegramTokens() {
        String token = "123456789:AAExampleTelegramCredential_123456789";
        Exception error = new RuntimeException("GET https://api.telegram.org/bot" + token + "/getUpdates");

        assertFalse(LogUtil.relevantStack(error).contains(token));
        assertTrue(LogUtil.relevantStack(error).contains(SecretRedactor.REDACTED));
    }

    @Test
    void exceptionHelpersRedactTelegramTokens() {
        String token = "123456789:AAExampleTelegramCredential_123456789";
        Exception error = new RuntimeException("GET https://api.telegram.org/bot" + token + "/getUpdates");

        assertFalse(LogUtil.rootMessage(error).contains(token));
        assertFalse(LogUtil.trimmedStack(error).contains(token));
        assertTrue(LogUtil.rootMessage(error).contains(SecretRedactor.REDACTED));
    }

    @Test
    void relevantStackExceptionOverloadsMirrorTheThrowableOnes() {
        Exception e = new IllegalStateException("boom");
        assertEquals(LogUtil.relevantStack((Throwable) e, 3), LogUtil.relevantStack(e, 3));
        assertEquals(LogUtil.relevantStack((Throwable) e), LogUtil.relevantStack(e));
    }

    @Test
    void relevantStackKeepsLocationlessFrameLikeLine() {
        // A message that itself carries a frame line with no "(File.java:1)" suffix must be filtered
        // without error, and that line survives because its class is not plumbing.
        Exception e = new IllegalStateException("boom\n\tat com.example.EmbeddedFrame.run");
        String stack = LogUtil.relevantStack(e);
        assertTrue(stack.contains("com.example.EmbeddedFrame.run"));
    }

    @Test
    void relevantThrowableReusesASharedCauseAndSuppressed() {
        Exception shared = new IllegalStateException("shared");
        Exception outer = new IllegalStateException("outer", shared);
        outer.addSuppressed(shared);

        Throwable trimmed = LogUtil.relevantThrowable(outer);

        assertEquals(
                "java.lang.IllegalStateException: shared", trimmed.getCause().toString());
        assertEquals(1, trimmed.getSuppressed().length);
        // The same instance appearing twice must be trimmed once and reused, not duplicated.
        assertSame(trimmed.getCause(), trimmed.getSuppressed()[0]);
    }

    @Test
    void relevantThrowableStopsDescendingAtDepthLimit() {
        Exception chain = new IllegalStateException("c0");
        for (int i = 1; i <= 15; i++) {
            chain = new IllegalStateException("c" + i, chain);
        }

        Throwable trimmed = LogUtil.relevantThrowable(chain);
        int depth = 0;
        for (Throwable cursor = trimmed; cursor.getCause() != null; cursor = cursor.getCause()) {
            depth++;
        }
        assertEquals(10, depth, "the trimmed cause chain must stop at MAX_CAUSE_DEPTH");
    }

    @Test
    void relevantThrowableDoesNotRecurseOnASelfCause() {
        Throwable self = new IllegalStateException("self-caused") {
            private static final long serialVersionUID = 1L;

            @Override
            public Throwable getCause() {
                return this;
            }
        };

        Throwable trimmed = LogUtil.relevantThrowable(self);

        assertNull(trimmed.getCause(), "a throwable reporting itself as its cause must terminate");
        assertTrue(trimmed.toString().contains("self-caused"));
    }
}
