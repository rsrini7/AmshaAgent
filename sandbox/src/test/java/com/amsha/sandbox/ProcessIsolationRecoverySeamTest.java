package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import com.amsha.core.sandbox.SandboxRecoveryEvidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessIsolationRecoverySeamTest {

    private ProcessIsolationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ProcessIsolationExecutor();
        executor.podmanPath = "non-existent-podman-binary-12345";
    }

    @Test
    void inspectAttemptWithMissingHandleReturnsMissing() {
        SandboxRecoveryEvidence evNull = executor.inspectAttempt("ws1", "att1", null, null);
        assertTrue(evNull instanceof SandboxRecoveryEvidence.Missing);

        SandboxRecoveryEvidence evBlank = executor.inspectAttempt("ws1", "att1", "   ", null);
        assertTrue(evBlank instanceof SandboxRecoveryEvidence.Missing);
    }

    @Test
    void inspectAttemptWithUnsupportedHandleReturnsInconclusive() {
        SandboxRecoveryEvidence ev = executor.inspectAttempt("ws1", "att1", "unknown-handle:abc", "identity");
        assertTrue(ev instanceof SandboxRecoveryEvidence.Inconclusive);
    }

    @Test
    void inspectAttemptWithMalformedProcessIdentityReturnsInconclusive() {
        SandboxRecoveryEvidence ev1 = executor.inspectAttempt("ws1", "att1", "process:att1", "malformed");
        assertTrue(ev1 instanceof SandboxRecoveryEvidence.Inconclusive);

        SandboxRecoveryEvidence ev2 =
                executor.inspectAttempt("ws1", "att1", "process:att1", "pid:notanumber;start:123");
        assertTrue(ev2 instanceof SandboxRecoveryEvidence.Inconclusive);

        SandboxRecoveryEvidence ev3 = executor.inspectAttempt("ws1", "att1", "process:att1", "pid:123");
        assertTrue(ev3 instanceof SandboxRecoveryEvidence.Inconclusive);
    }

    @Test
    void inspectAttemptWithProcessHandleAndCurrentJvmProcessVerifiedRunning() {
        ProcessHandle current = ProcessHandle.current();
        long pid = current.pid();
        Instant startedAt = current.info().startInstant().orElse(Instant.now());
        String identity = "pid:" + pid + ";start:" + startedAt.toEpochMilli();

        SandboxRecoveryEvidence ev = executor.inspectAttempt("ws1", "att1", "process:att1", identity);
        assertTrue(ev instanceof SandboxRecoveryEvidence.RunningVerified);
        SandboxRecoveryEvidence.RunningVerified running = (SandboxRecoveryEvidence.RunningVerified) ev;
        assertEquals("process:att1", running.executionHandle());
        assertEquals(identity, running.processStartIdentity());
    }

    @Test
    void inspectAttemptWithProcessHandleRecycledPidReturnsOwnershipMismatch() {
        ProcessHandle current = ProcessHandle.current();
        long pid = current.pid();
        Instant startedAt = current.info().startInstant().orElse(Instant.now());
        String recycledIdentity = "pid:" + pid + ";start:" + (startedAt.toEpochMilli() - 999_999L);

        SandboxRecoveryEvidence ev = executor.inspectAttempt("ws1", "att1", "process:att1", recycledIdentity);
        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void inspectAttemptWithProcessHandleDeadPidReturnsMissing() {
        // PID 9999999 is extraordinarily unlikely to exist
        String deadIdentity = "pid:9999999;start:1000";
        SandboxRecoveryEvidence ev = executor.inspectAttempt("ws1", "att1", "process:att1", deadIdentity);
        assertTrue(ev instanceof SandboxRecoveryEvidence.Missing);
    }

    @Test
    void terminateRunningAttemptHandlesMissingAndDeadProcessesSafely() {
        assertTrue(executor.terminateRunningAttempt("ws1", "att1", null, null));
        assertTrue(executor.terminateRunningAttempt("ws1", "att1", "process:att1", "pid:9999999;start:1000"));
        assertTrue(executor.terminateRunningAttempt("ws1", "att1", "process:att1", "malformed"));
    }

    @Test
    void inspectAttemptWithMismatchedAttemptIdReturnsOwnershipMismatch() {
        ProcessHandle current = ProcessHandle.current();
        long pid = current.pid();
        Instant startedAt = current.info().startInstant().orElse(Instant.now());
        String identity = "pid:" + pid + ";start:" + startedAt.toEpochMilli();

        SandboxRecoveryEvidence ev = executor.inspectAttempt("ws1", "att1", "process:other-attempt", identity);
        assertTrue(ev instanceof SandboxRecoveryEvidence.OwnershipMismatch);
    }

    @Test
    void terminateRunningAttemptWithMismatchedAttemptIdReturnsFalse() {
        assertFalse(executor.terminateRunningAttempt("ws1", "att1", "process:other-attempt", "pid:123;start:456"));
    }
}
