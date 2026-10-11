package com.amsha.common.util;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;

public final class LogUtil {

    /**
     * Class-name prefixes that belong to test runners, build tooling, CDI plumbing and JDK
     * reflection/collection internals rather than to application or library code. They carry no
     * diagnostic value and are exactly what makes expected-exception tests flood the log, so
     * {@link #relevantStack(Throwable)} and {@link #relevantThrowable(Throwable)} drop them.
     */
    private static final List<String> NOISE_FRAME_PREFIXES = List.of(
            "org.junit.",
            "org.mockito.",
            "org.apache.maven.",
            "org.apache.surefire.",
            "org.gradle.",
            "org.jboss.weld.",
            "io.quarkus.test.",
            "jdk.internal.reflect.",
            "java.lang.reflect.",
            "sun.reflect.",
            "java.util.");

    /** Upper bound on retained frames, so one failure cannot dominate the log. */
    private static final int DEFAULT_MAX_FRAMES = 20;

    /** Upper bound on the retained cause chain, guarding against cyclic causes. */
    private static final int MAX_CAUSE_DEPTH = 10;

    private LogUtil() {}

    public static String rootMessage(Exception e) {
        String msg = ExceptionUtils.getRootCauseMessage(e);
        return SecretRedactor.redact(msg != null ? msg : e.getClass().getSimpleName());
    }

    public static String rootMessage(Throwable e) {
        String msg = ExceptionUtils.getRootCauseMessage(e);
        return SecretRedactor.redact(msg != null ? msg : e.getClass().getSimpleName());
    }

    public static String trimmedStack(Exception e, int maxLines) {
        return trimmedStack((Throwable) e, maxLines);
    }

    public static String trimmedStack(Throwable e, int maxLines) {
        return SecretRedactor.redact(ExceptionUtils.getStackTrace(e))
                .lines()
                .limit(maxLines)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
    }

    public static String trimmedStack(Exception e) {
        return trimmedStack(e, 10);
    }

    public static String trimmedStack(Throwable e) {
        return trimmedStack(e, 10);
    }

    /**
     * Renders a throwable as text containing only the frames that are actually relevant: the
     * exception header(s) plus application and library frames, with test-runner, build, reflection
     * and JDK-collection plumbing removed.
     *
     * <p>Prefer {@link #relevantThrowable(Throwable)} when the call is a logger that accepts a
     * throwable: this text form is routed through {@link SecretRedactor}, whose JWT pattern matches
     * dotted triples and can therefore rewrite a frame's class and method names.
     */
    public static String relevantStack(Throwable e, int maxLines) {
        if (e == null) {
            return "";
        }
        // Filter before redacting: redaction can rewrite a frame's package prefix, which would
        // otherwise stop a plumbing frame from being dropped.
        String relevant = ExceptionUtils.getStackTrace(e)
                .lines()
                .filter(line -> !isNoiseFrame(line))
                .limit(maxLines)
                .collect(Collectors.joining("\n"));
        return SecretRedactor.redact(relevant);
    }

    public static String relevantStack(Throwable e) {
        return relevantStack(e, DEFAULT_MAX_FRAMES);
    }

    public static String relevantStack(Exception e, int maxLines) {
        return relevantStack((Throwable) e, maxLines);
    }

    public static String relevantStack(Exception e) {
        return relevantStack((Throwable) e, DEFAULT_MAX_FRAMES);
    }

    /**
     * Returns a throwable that renders with only the relevant frames, for handing to a logger as the
     * cause. The original type and message are preserved, the cause and suppressed chains are kept,
     * and plumbing frames are dropped.
     *
     * <p>Use this in preference to {@link #relevantStack(Throwable)} at logger call sites: a logger
     * takes the stack from {@link Throwable#getStackTrace()}, so message-level secret redaction
     * cannot rewrite the frame's class or method names.
     */
    public static Throwable relevantThrowable(Throwable e) {
        return e == null ? null : relevantThrowable(e, new IdentityHashMap<>(), 0);
    }

    private static Throwable relevantThrowable(Throwable source, Map<Throwable, Throwable> seen, int depth) {
        Throwable existing = seen.get(source);
        if (existing != null) {
            return existing;
        }
        Throwable cause = source.getCause();
        Throwable trimmedCause = cause == null || cause == source || depth >= MAX_CAUSE_DEPTH
                ? null
                : relevantThrowable(cause, seen, depth + 1);

        RelevantStackThrowable copy = new RelevantStackThrowable(source.toString(), trimmedCause);
        seen.put(source, copy);
        copy.setStackTrace(relevantFrames(source.getStackTrace()));
        for (Throwable suppressed : source.getSuppressed()) {
            copy.addSuppressed(relevantThrowable(suppressed, seen, depth + 1));
        }
        return copy;
    }

    private static StackTraceElement[] relevantFrames(StackTraceElement[] frames) {
        return Arrays.stream(frames)
                .filter(frame -> !isNoiseClass(frame.getClassName()))
                .limit(DEFAULT_MAX_FRAMES)
                .toArray(StackTraceElement[]::new);
    }

    private static boolean isNoiseFrame(String line) {
        if (!line.startsWith("\tat ")) {
            return false;
        }
        String frame = line.substring(4);
        int paren = frame.indexOf('(');
        String head = paren < 0 ? frame : frame.substring(0, paren);
        // A rendered frame may carry a module prefix, e.g. "java.base/java.util.ArrayList.forEach".
        int slash = head.indexOf('/');
        if (slash > 0) {
            head = head.substring(slash + 1);
        }
        return isNoiseClass(head);
    }

    private static boolean isNoiseClass(String className) {
        return NOISE_FRAME_PREFIXES.stream().anyMatch(className::startsWith);
    }

    public static String ctx(String sessionId, String role) {
        return "[session=" + sessionId + " role=" + role + "]";
    }

    public static String ctx(String sessionId, String role, String tool) {
        return "[session=" + sessionId + " role=" + role + " tool=" + tool + "]";
    }

    /**
     * Renders with the original header (so the log still reads {@code java.lang.Foo: message}) while
     * carrying a caller-supplied, already-trimmed stack trace.
     */
    private static final class RelevantStackThrowable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RelevantStackThrowable(String header, Throwable cause) {
            super(header, cause, true, true);
        }

        @Override
        public String toString() {
            return getMessage();
        }
    }
}
