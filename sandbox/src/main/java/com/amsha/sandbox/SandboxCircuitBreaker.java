package com.amsha.sandbox;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class SandboxCircuitBreaker {

    private static final Logger log = Logger.getLogger(SandboxCircuitBreaker.class);

    public enum CircuitBreakerState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
    private final AtomicInteger podmanFailures = new AtomicInteger(0);
    private final AtomicLong openedAtWallClock = new AtomicLong(0L);
    private final AtomicLong openedAtNano = new AtomicLong(0L);

    @ConfigProperty(name = "amsha.sandbox.circuit-breaker.failure-threshold", defaultValue = "3")
    int podmanFailureThreshold = 3;

    @ConfigProperty(name = "amsha.sandbox.circuit-breaker.reset-timeout-seconds", defaultValue = "30")
    int resetTimeoutSeconds = 30;

    // For testability
    protected long currentNanoTime() {
        return System.nanoTime();
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public void openCircuit() {
        state.set(CircuitBreakerState.OPEN);
        openedAtWallClock.set(currentTimeMillis());
        openedAtNano.set(currentNanoTime());
    }

    public int getPodmanFailureThreshold() {
        return podmanFailureThreshold;
    }

    public int getResetTimeoutSeconds() {
        return resetTimeoutSeconds;
    }

    /**
     * Called at the start of each Podman attempt. Handles OPEN→HALF_OPEN transition.
     * Returns true if a Podman attempt should proceed, false if circuit is OPEN
     * and reset timeout has not elapsed.
     */
    public boolean shouldAttemptPodman() {
        CircuitBreakerState current = state.get();
        if (current == CircuitBreakerState.OPEN) {
            long elapsedNanos = currentNanoTime() - openedAtNano.get();
            if (elapsedNanos >= TimeUnit.SECONDS.toNanos(resetTimeoutSeconds)) {
                log.infof(
                        "Podman circuit breaker reset timeout reached (circuit was opened at wall-clock %s). Transitioning to HALF_OPEN.",
                        Instant.ofEpochMilli(openedAtWallClock.get()));
                state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
                return true;
            }
            return false;
        }
        return true; // CLOSED or HALF_OPEN
    }

    /**
     * Called after a successful Podman execution.
     */
    public void recordSuccess() {
        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            log.infof("Podman successful in HALF_OPEN state. Closing circuit breaker.");
        }
        state.set(CircuitBreakerState.CLOSED);
        podmanFailures.set(0);
    }

    /**
     * Called after a Podman infrastructure failure (NOT ToolExecutionException).
     * @param stateAtCallTime the circuit state captured before the failed attempt
     */
    public void recordFailure(CircuitBreakerState stateAtCallTime) {
        int failures = podmanFailures.incrementAndGet();
        log.errorf("Podman execution failed (failureCount=%d, state=%s)", failures, stateAtCallTime);
        if (stateAtCallTime == CircuitBreakerState.HALF_OPEN || failures >= podmanFailureThreshold) {
            log.errorf("Podman failed. Opening circuit breaker (state was %s).", stateAtCallTime);
            openCircuit();
        }
    }

    public CircuitBreakerState getState() {
        return state.get();
    }
}
