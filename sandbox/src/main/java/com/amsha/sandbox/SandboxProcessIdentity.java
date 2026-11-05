package com.amsha.sandbox;

import java.time.Instant;

/** Produces a process handle that can be matched against a later PID reuse. */
final class SandboxProcessIdentity {
    private SandboxProcessIdentity() {}

    static String from(Process process) {
        try {
            long pid = process.pid();
            ProcessHandle handle = process.toHandle();
            if (handle == null || handle.info() == null) {
                return null;
            }
            return handle.info()
                    .startInstant()
                    .map(startedAt -> format(pid, startedAt))
                    .orElse(null);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String format(long pid, Instant startedAt) {
        return "pid:" + pid + ";start:" + startedAt.toEpochMilli();
    }
}
