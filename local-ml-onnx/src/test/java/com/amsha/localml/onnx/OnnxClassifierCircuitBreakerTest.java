package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class OnnxClassifierCircuitBreakerTest {

    @Test
    void opensAtThresholdAndClosesAfterSuccessfulHalfOpenProbe() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        OnnxClassifierCircuitBreaker breaker = new OnnxClassifierCircuitBreaker(2, Duration.ofSeconds(30), clock);

        breaker.recordFailure(breaker.tryAcquire().orElseThrow());
        assertEquals(OnnxClassifierCircuitBreaker.State.CLOSED, breaker.state());
        breaker.recordFailure(breaker.tryAcquire().orElseThrow());
        assertEquals(OnnxClassifierCircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.tryAcquire().isEmpty());

        clock.advance(Duration.ofSeconds(30));
        var probe = breaker.tryAcquire().orElseThrow();
        assertTrue(probe.halfOpen());
        assertTrue(breaker.tryAcquire().isEmpty());
        breaker.recordSuccess(probe);

        assertEquals(OnnxClassifierCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquire().isPresent());
    }

    @Test
    void neutralHalfOpenOutcomeDoesNotCountAsModelFailure() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));
        OnnxClassifierCircuitBreaker breaker = new OnnxClassifierCircuitBreaker(1, Duration.ofSeconds(30), clock);
        breaker.recordFailure(breaker.tryAcquire().orElseThrow());
        clock.advance(Duration.ofSeconds(30));

        breaker.recordNeutral(breaker.tryAcquire().orElseThrow());

        assertEquals(OnnxClassifierCircuitBreaker.State.OPEN, breaker.state());
        assertTrue(breaker.tryAcquire().isPresent());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
