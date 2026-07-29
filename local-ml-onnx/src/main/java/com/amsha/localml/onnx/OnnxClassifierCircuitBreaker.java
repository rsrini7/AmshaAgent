package com.amsha.localml.onnx;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Per-bundle closed/open/half-open breaker for standalone classifier inference. */
final class OnnxClassifierCircuitBreaker {

    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant retryAt = Instant.MIN;
    private long epoch;

    OnnxClassifierCircuitBreaker(int failureThreshold, Duration cooldown) {
        this(failureThreshold, cooldown, Clock.systemUTC());
    }

    OnnxClassifierCircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold must be positive");
        this.failureThreshold = failureThreshold;
        this.cooldown = requirePositive(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized Optional<Permit> tryAcquire() {
        Instant now = clock.instant();
        if (state == State.OPEN) {
            if (now.isBefore(retryAt)) return Optional.empty();
            state = State.HALF_OPEN;
            return Optional.of(new Permit(epoch, true));
        }
        if (state == State.HALF_OPEN) return Optional.empty();
        return Optional.of(new Permit(epoch, false));
    }

    synchronized void recordSuccess(Permit permit) {
        Objects.requireNonNull(permit, "permit");
        if (permit.epoch() != epoch) return;
        if (permit.halfOpen()) {
            if (state != State.HALF_OPEN) return;
            state = State.CLOSED;
        } else if (state != State.CLOSED) {
            return;
        }
        consecutiveFailures = 0;
        retryAt = Instant.MIN;
    }

    synchronized void recordFailure(Permit permit) {
        Objects.requireNonNull(permit, "permit");
        if (permit.epoch() != epoch) return;
        if (permit.halfOpen()) {
            if (state == State.HALF_OPEN) open();
            return;
        }
        if (state != State.CLOSED) return;
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) open();
    }

    /** Releases a half-open probe when no model execution occurred, without counting a failure. */
    synchronized void recordNeutral(Permit permit) {
        Objects.requireNonNull(permit, "permit");
        if (permit.epoch() != epoch || !permit.halfOpen() || state != State.HALF_OPEN) return;
        state = State.OPEN;
        retryAt = clock.instant();
        epoch++;
    }

    synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        retryAt = clock.instant().plus(cooldown);
        epoch++;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    record Permit(long epoch, boolean halfOpen) {}
}
