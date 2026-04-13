package com.arcclaw.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SandboxCircuitBreakerTest {

    private TestableSandboxCircuitBreaker circuitBreaker;

    static class TestableSandboxCircuitBreaker extends SandboxCircuitBreaker {
        long mockNanoTime = 0L;
        long mockTimeMillis = 0L;

        @Override
        protected long currentNanoTime() {
            return mockNanoTime;
        }

        @Override
        protected long currentTimeMillis() {
            return mockTimeMillis;
        }
    }

    @BeforeEach
    void setUp() {
        circuitBreaker = new TestableSandboxCircuitBreaker();
        circuitBreaker.mockNanoTime = System.nanoTime();
        circuitBreaker.mockTimeMillis = System.currentTimeMillis();
        
        // Default values usually injected by Quarkus, setting them manually for plain Mockito/JUnit
        circuitBreaker.podmanFailureThreshold = 3;
        circuitBreaker.resetTimeoutSeconds = 30;
    }

    @Test
    void testShouldAttemptPodman_closedState_returnsTrue() {
        assertTrue(circuitBreaker.shouldAttemptPodman());
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.CLOSED, circuitBreaker.getState());
    }

    @Test
    void testShouldAttemptPodman_openState_returnsFalse_beforeTimeout() {
        circuitBreaker.openCircuit();
        assertFalse(circuitBreaker.shouldAttemptPodman());
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.OPEN, circuitBreaker.getState());
    }

    @Test
    void testShouldAttemptPodman_openState_transitionsToHalfOpen_afterTimeout() {
        circuitBreaker.resetTimeoutSeconds = 1; // 1 second for fast test
        circuitBreaker.openCircuit();
        
        // Advance monotonic time by 1.1 seconds
        circuitBreaker.mockNanoTime += TimeUnit.MILLISECONDS.toNanos(1100);
        
        assertTrue(circuitBreaker.shouldAttemptPodman());
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testShouldAttemptPodman_immuneToWallClockShifts() {
        circuitBreaker.resetTimeoutSeconds = 30;
        circuitBreaker.openCircuit();
        
        // Simulate a system clock adjustment backwards by 1 hour (NTP sync, etc.)
        circuitBreaker.mockTimeMillis -= TimeUnit.HOURS.toMillis(1);
        
        // Monotonic time correctly advances 31 seconds
        circuitBreaker.mockNanoTime += TimeUnit.SECONDS.toNanos(31);
        
        // Should STILL successfully transition to HALF_OPEN because we measure nanos, not millis! 
        assertTrue(circuitBreaker.shouldAttemptPodman(), "Should reset based on monotonic time regardless of wall-clock shifts");
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testRecordSuccess_closesCircuit() {
        circuitBreaker.openCircuit();
        // Force to HALF_OPEN via shouldAttemptPodman after artificial timeout
        circuitBreaker.resetTimeoutSeconds = 1;
        circuitBreaker.mockNanoTime += TimeUnit.SECONDS.toNanos(2);
        circuitBreaker.shouldAttemptPodman();
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());

        circuitBreaker.recordSuccess();
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.CLOSED, circuitBreaker.getState());
    }

    @Test
    void testRecordFailure_opensCircuit_afterThreshold() {
        SandboxCircuitBreaker.CircuitBreakerState state = circuitBreaker.getState();
        circuitBreaker.recordFailure(state); // 1
        circuitBreaker.recordFailure(state); // 2
        circuitBreaker.recordFailure(state); // 3 -> Opening
        
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.OPEN, circuitBreaker.getState());
    }

    @Test
    void testRecordFailure_opensCircuit_whenHalfOpen() {
        circuitBreaker.openCircuit();
        circuitBreaker.resetTimeoutSeconds = 1;
        circuitBreaker.mockNanoTime += TimeUnit.SECONDS.toNanos(2);
        circuitBreaker.shouldAttemptPodman();
        
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
        
        circuitBreaker.recordFailure(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN);
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.OPEN, circuitBreaker.getState());
    }

}
