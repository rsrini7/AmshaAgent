package com.amsha.persistence.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests verifying status enums and lifecycle transition contracts per Proposal §12.3 and §12.4.
 */
class ToolAttemptStatusContractTest {

    @Test
    void testToolAttemptStatusTerminalStates() {
        assertFalse(ToolAttemptStatus.ADMITTED.isTerminal());
        assertFalse(ToolAttemptStatus.LAUNCH_REQUESTED.isTerminal());
        assertFalse(ToolAttemptStatus.RUNNING.isTerminal());

        assertTrue(ToolAttemptStatus.SETTLED_SUCCESS.isTerminal());
        assertTrue(ToolAttemptStatus.SETTLED_ERROR.isTerminal());
        assertTrue(ToolAttemptStatus.TIMED_OUT.isTerminal());
        assertTrue(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH.isTerminal());
        assertTrue(ToolAttemptStatus.INDETERMINATE.isTerminal());
    }

    @Test
    void testToolAttemptStatusSettledStates() {
        assertTrue(ToolAttemptStatus.SETTLED_SUCCESS.isSettled());
        assertTrue(ToolAttemptStatus.SETTLED_ERROR.isSettled());

        assertFalse(ToolAttemptStatus.ADMITTED.isSettled());
        assertFalse(ToolAttemptStatus.LAUNCH_REQUESTED.isSettled());
        assertFalse(ToolAttemptStatus.RUNNING.isSettled());
        assertFalse(ToolAttemptStatus.TIMED_OUT.isSettled());
        assertFalse(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH.isSettled());
        assertFalse(ToolAttemptStatus.INDETERMINATE.isSettled());
    }

    @Test
    void testToolAttemptStatusLegalTransitionsFromAdmitted() {
        assertTrue(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.LAUNCH_REQUESTED));
        assertTrue(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH));

        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.RUNNING));
        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.SETTLED_SUCCESS));
        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.SETTLED_ERROR));
        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.TIMED_OUT));
        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.INDETERMINATE));
        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(ToolAttemptStatus.ADMITTED));
        assertFalse(ToolAttemptStatus.ADMITTED.canTransitionTo(null));
    }

    @Test
    void testToolAttemptStatusLegalTransitionsFromLaunchRequested() {
        assertTrue(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.RUNNING));
        assertTrue(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH));
        assertTrue(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.SETTLED_SUCCESS));
        assertTrue(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.SETTLED_ERROR));
        assertTrue(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.TIMED_OUT));
        assertTrue(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.INDETERMINATE));

        assertFalse(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.ADMITTED));
        assertFalse(ToolAttemptStatus.LAUNCH_REQUESTED.canTransitionTo(ToolAttemptStatus.LAUNCH_REQUESTED));
    }

    @Test
    void testToolAttemptStatusLegalTransitionsFromRunning() {
        assertTrue(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.SETTLED_SUCCESS));
        assertTrue(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.SETTLED_ERROR));
        assertTrue(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.TIMED_OUT));
        assertTrue(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.INDETERMINATE));

        assertFalse(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.ADMITTED));
        assertFalse(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.LAUNCH_REQUESTED));
        assertFalse(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH));
        assertFalse(ToolAttemptStatus.RUNNING.canTransitionTo(ToolAttemptStatus.RUNNING));
    }

    @Test
    void testTerminalStatesAreImmutable() {
        ToolAttemptStatus[] terminals = {
            ToolAttemptStatus.SETTLED_SUCCESS,
            ToolAttemptStatus.SETTLED_ERROR,
            ToolAttemptStatus.TIMED_OUT,
            ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH,
            ToolAttemptStatus.INDETERMINATE
        };

        for (ToolAttemptStatus terminal : terminals) {
            for (ToolAttemptStatus target : ToolAttemptStatus.values()) {
                assertFalse(
                        terminal.canTransitionTo(target),
                        "Terminal status " + terminal + " must not transition to " + target);
            }
            assertFalse(terminal.canTransitionTo(null));
        }
    }

    // =========================================================================
    // 2. Idempotency Mode Tests (§12.4)
    // =========================================================================

    @Test
    void testIdempotencyModeParsingAndFailClosed() {
        assertEquals(ToolAttemptIdempotencyMode.IDEMPOTENT, ToolAttemptIdempotencyMode.fromString("IDEMPOTENT"));
        assertEquals(ToolAttemptIdempotencyMode.IDEMPOTENT, ToolAttemptIdempotencyMode.fromString("idempotent"));
        assertEquals(ToolAttemptIdempotencyMode.AT_MOST_ONCE, ToolAttemptIdempotencyMode.fromString("AT_MOST_ONCE"));
        assertEquals(ToolAttemptIdempotencyMode.AT_MOST_ONCE, ToolAttemptIdempotencyMode.fromString("at_most_once"));
        assertEquals(
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT, ToolAttemptIdempotencyMode.fromString("NON_IDEMPOTENT"));

        // Fail closed to NON_IDEMPOTENT
        assertEquals(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, ToolAttemptIdempotencyMode.fromString(null));
        assertEquals(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, ToolAttemptIdempotencyMode.fromString(""));
        assertEquals(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, ToolAttemptIdempotencyMode.fromString("   "));
        assertEquals(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, ToolAttemptIdempotencyMode.fromString("UNKNOWN_MODE"));
        assertEquals(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, ToolAttemptIdempotencyMode.fromString("RETRYABLE"));
    }

    // =========================================================================
    // 3. Audit Event Kind Tests (§12.4)
    // =========================================================================

    @Test
    void testAuditEventKindClassification() {
        assertTrue(ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED.isResolutionEvent());
        assertTrue(ToolAttemptAuditEventKind.ACTION_RESOLVED.isResolutionEvent());
        assertFalse(ToolAttemptAuditEventKind.ACTION_RESOLUTION_AUTHORIZED.isAttemptEvent());
        assertFalse(ToolAttemptAuditEventKind.ACTION_RESOLVED.isAttemptEvent());

        assertTrue(ToolAttemptAuditEventKind.ATTEMPT_ADMITTED.isAttemptEvent());
        assertTrue(ToolAttemptAuditEventKind.LAUNCH_REQUESTED.isAttemptEvent());
        assertTrue(ToolAttemptAuditEventKind.ATTEMPT_SETTLED.isAttemptEvent());
        assertTrue(ToolAttemptAuditEventKind.ACTION_LOCKED.isAttemptEvent());
        assertFalse(ToolAttemptAuditEventKind.ATTEMPT_ADMITTED.isResolutionEvent());
        assertFalse(ToolAttemptAuditEventKind.LAUNCH_REQUESTED.isResolutionEvent());
        assertFalse(ToolAttemptAuditEventKind.ATTEMPT_SETTLED.isResolutionEvent());
        assertFalse(ToolAttemptAuditEventKind.ACTION_LOCKED.isResolutionEvent());
    }

    // =========================================================================
    // 4. Resolution Status Tests (§12.4)
    // =========================================================================

    @Test
    void testResolutionStatusTransitions() {
        assertTrue(ToolAttemptResolutionStatus.PENDING.canTransition());
        assertFalse(ToolAttemptResolutionStatus.PENDING.isTerminal());

        assertFalse(ToolAttemptResolutionStatus.APPLIED.canTransition());
        assertTrue(ToolAttemptResolutionStatus.APPLIED.isTerminal());

        assertFalse(ToolAttemptResolutionStatus.EXPIRED.canTransition());
        assertTrue(ToolAttemptResolutionStatus.EXPIRED.isTerminal());
    }

    // =========================================================================
    // 5. Retry Eligibility Matrix Tests (§3 and §12.4)
    // =========================================================================

    @Test
    void testRetryEligibilityRejectsNullOrNonTerminalOrSuccess() {
        assertFalse(ToolAttemptStatus.SETTLED_SUCCESS.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, false));
        assertFalse(ToolAttemptStatus.SETTLED_SUCCESS.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, true));
        assertFalse(
                ToolAttemptStatus.SETTLED_SUCCESS.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, false));
        assertFalse(
                ToolAttemptStatus.SETTLED_SUCCESS.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, true));
        assertFalse(
                ToolAttemptStatus.SETTLED_SUCCESS.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, false));
        assertFalse(
                ToolAttemptStatus.SETTLED_SUCCESS.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, true));

        assertFalse(ToolAttemptStatus.ADMITTED.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, true));
        assertFalse(ToolAttemptStatus.LAUNCH_REQUESTED.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, true));
        assertFalse(ToolAttemptStatus.RUNNING.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, true));

        assertFalse(ToolAttemptStatus.SETTLED_ERROR.isEligibleForRetry(null, true));
    }

    @Test
    void testRetryEligibilityAtMostOnce() {
        assertTrue(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH.isEligibleForRetry(
                ToolAttemptIdempotencyMode.AT_MOST_ONCE, false));
        assertTrue(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH.isEligibleForRetry(
                ToolAttemptIdempotencyMode.AT_MOST_ONCE, true));

        assertFalse(ToolAttemptStatus.SETTLED_ERROR.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, false));
        assertFalse(ToolAttemptStatus.SETTLED_ERROR.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, true));
        assertFalse(ToolAttemptStatus.TIMED_OUT.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, false));
        assertFalse(ToolAttemptStatus.TIMED_OUT.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, true));
        assertFalse(ToolAttemptStatus.INDETERMINATE.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, false));
        assertFalse(ToolAttemptStatus.INDETERMINATE.isEligibleForRetry(ToolAttemptIdempotencyMode.AT_MOST_ONCE, true));
    }

    @Test
    void testRetryEligibilityIdempotent() {
        assertTrue(ToolAttemptStatus.SETTLED_ERROR.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, false));
        assertTrue(ToolAttemptStatus.TIMED_OUT.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, false));
        assertTrue(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH.isEligibleForRetry(
                ToolAttemptIdempotencyMode.IDEMPOTENT, false));

        assertFalse(ToolAttemptStatus.INDETERMINATE.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, false));
        assertTrue(ToolAttemptStatus.INDETERMINATE.isEligibleForRetry(ToolAttemptIdempotencyMode.IDEMPOTENT, true));
    }

    @Test
    void testRetryEligibilityNonIdempotent() {
        assertTrue(ToolAttemptStatus.CANCELLED_BEFORE_LAUNCH.isEligibleForRetry(
                ToolAttemptIdempotencyMode.NON_IDEMPOTENT, false));

        assertFalse(
                ToolAttemptStatus.SETTLED_ERROR.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, false));
        assertTrue(ToolAttemptStatus.SETTLED_ERROR.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, true));

        assertFalse(ToolAttemptStatus.TIMED_OUT.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, false));
        assertTrue(ToolAttemptStatus.TIMED_OUT.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, true));

        assertFalse(
                ToolAttemptStatus.INDETERMINATE.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, false));
        assertTrue(ToolAttemptStatus.INDETERMINATE.isEligibleForRetry(ToolAttemptIdempotencyMode.NON_IDEMPOTENT, true));
    }
}
