package com.amsha.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.inject.Instance;

import java.util.Optional;

import com.amsha.persistence.api.AdvisoryLockPort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Validates the activity-heartbeat fix: the resume mark must only expire after a
 * genuine period of <em>no progress</em>, not a fixed window from turn start. This
 * is the root-cause fix for the "busy message loses its goal / status shows
 * COMPLETED while still working" reports.
 */
class ResumeCoordinationGuardTest {

    private ResumeCoordinationGuard newGuard() {
        ResumeCoordinationGuard guard = new ResumeCoordinationGuard();
        // Force a 0s staleness window so we can observe expiry deterministically.
        guard.staleResumeSeconds = 0;
        return guard;
    }

    @Test
    void markResumingStoresGoalAndPhase() {
        ResumeCoordinationGuard guard = newGuard();
        assertTrue(guard.markResuming("s1", false, "Diagnose memory leak", "RUNNING"));
        assertTrue(guard.isResuming("s1"));
        assertEquals(Optional.of("Diagnose memory leak"), guard.getActiveGoal("s1"));
        assertEquals(Optional.of("RUNNING"), guard.getActivePhase("s1"));
    }

    @Test
    void markResumingIsExclusive() {
        ResumeCoordinationGuard guard = newGuard();
        assertTrue(guard.markResuming("s1", false, "first", "RUNNING"));
        assertFalse(guard.markResuming("s1", false, "second", "RUNNING"));
    }

    @Test
    void markExpiresWhenNoProgressBeyondStaleWindow() throws InterruptedException {
        ResumeCoordinationGuard guard = newGuard();
        guard.markResuming("s1", false, "goal", "RUNNING");
        Thread.sleep(1100); // exceed the 0s stale window with no refresh
        assertFalse(guard.isResuming("s1"), "mark should expire after a period of no progress");
        assertEquals(Optional.empty(), guard.getActiveGoal("s1"));
        assertEquals(Optional.empty(), guard.getActivePhase("s1"));
    }

    @Test
    void touchKeepsMarkAliveAcrossLongRun() throws InterruptedException {
        ResumeCoordinationGuard guard = newGuard();
        guard.markResuming("s1", false, "goal", "RUNNING");
        // Simulate a long-running turn where a step completes every >stale-window.
        for (int i = 0; i < 3; i++) {
            Thread.sleep(1100);
            guard.touch("s1", "TOOL:read_file");
        }
        assertTrue(guard.isResuming("s1"), "periodic activity refresh must prevent expiry");
        assertEquals(Optional.of("TOOL:read_file"), guard.getActivePhase("s1"));
    }

    @Test
    void touchRefreshesParentForDelegateSession() {
        ResumeCoordinationGuard guard = newGuard();
        guard.markResuming("parent", false, "goal", "RUNNING");
        // A delegate run reports progress under its own (prefixed) session id.
        guard.touch("parent:delegate:abc", "TOOL:search");
        assertEquals(Optional.of("TOOL:search"), guard.getActivePhase("parent"));
    }

    @Test
    void clearResumingReleases() {
        ResumeCoordinationGuard guard = newGuard();
        guard.markResuming("s1", false, "goal", "RUNNING");
        guard.clearResuming("s1");
        assertFalse(guard.isResuming("s1"));
        assertEquals(Optional.empty(), guard.getActiveGoal("s1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dbAdvisoryTouchRenewsLockAndPrefersLocalActiveMark() {
        ResumeCoordinationGuard guard = new ResumeCoordinationGuard();
        guard.staleResumeSeconds = 60;
        guard.dbAdvisoryEnabled = true;

        AdvisoryLockPort lockPort = Mockito.mock(AdvisoryLockPort.class);
        Instance<AdvisoryLockPort> instance = Mockito.mock(Instance.class);
        Mockito.when(instance.isUnsatisfied()).thenReturn(false);
        Mockito.when(instance.get()).thenReturn(lockPort);
        Mockito.when(lockPort.tryLock(ArgumentMatchers.eq("RESUME:s1"), ArgumentMatchers.anyInt()))
                .thenReturn(true);
        guard.lockPort = instance;

        assertTrue(guard.markResuming("s1", false, "goal", "RUNNING"));
        Mockito.verify(lockPort).tryLock(ArgumentMatchers.eq("RESUME:s1"), ArgumentMatchers.eq(60));

        // Even if DB lock expired externally, local active mark keeps isResuming true
        Mockito.when(lockPort.isLocked("RESUME:s1")).thenReturn(false);
        assertTrue(guard.isResuming("s1"));

        // Touch atomically renews the DB lock
        Mockito.when(lockPort.renew(ArgumentMatchers.eq("RESUME:s1"), ArgumentMatchers.anyInt()))
                .thenReturn(true);
        guard.touch("s1", "TOOL:write");
        Mockito.verify(lockPort).renew(ArgumentMatchers.eq("RESUME:s1"), ArgumentMatchers.eq(60));
        assertEquals(Optional.of("TOOL:write"), guard.getActivePhase("s1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dbAdvisoryIsResumingQueriesDbWhenNoLocalMark() {
        ResumeCoordinationGuard guard = new ResumeCoordinationGuard();
        guard.dbAdvisoryEnabled = true;

        AdvisoryLockPort lockPort = Mockito.mock(AdvisoryLockPort.class);
        Instance<AdvisoryLockPort> instance = Mockito.mock(Instance.class);
        Mockito.when(instance.isUnsatisfied()).thenReturn(false);
        Mockito.when(instance.get()).thenReturn(lockPort);
        guard.lockPort = instance;

        Mockito.when(lockPort.isLocked("RESUME:remote-sess")).thenReturn(true);
        assertTrue(guard.isResuming("remote-sess"));

        Mockito.when(lockPort.isLocked("RESUME:remote-sess")).thenReturn(false);
        assertFalse(guard.isResuming("remote-sess"));
    }
}
