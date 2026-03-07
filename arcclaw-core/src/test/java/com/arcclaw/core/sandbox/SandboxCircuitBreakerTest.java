package com.arcclaw.core.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SandboxCircuitBreakerTest {

    private SandboxCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = new SandboxCircuitBreaker();
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
    void testShouldAttemptPodman_openState_transitionsToHalfOpen_afterTimeout() throws InterruptedException {
        circuitBreaker.resetTimeoutSeconds = 1; // 1 second for fast test
        circuitBreaker.openCircuit();
        
        // Wait for timeout
        Thread.sleep(1100);
        
        assertTrue(circuitBreaker.shouldAttemptPodman());
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testRecordSuccess_closesCircuit() {
        circuitBreaker.openCircuit();
        // Force to HALF_OPEN via shouldAttemptPodman after artificial timeout
        circuitBreaker.resetTimeoutSeconds = 0;
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
        circuitBreaker.resetTimeoutSeconds = 0;
        circuitBreaker.shouldAttemptPodman();
        
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
        
        circuitBreaker.recordFailure(SandboxCircuitBreaker.CircuitBreakerState.HALF_OPEN);
        assertEquals(SandboxCircuitBreaker.CircuitBreakerState.OPEN, circuitBreaker.getState());
    }
}
