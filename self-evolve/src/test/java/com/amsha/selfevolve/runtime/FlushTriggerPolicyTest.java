package com.amsha.selfevolve.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlushTriggerPolicyTest {

    @Test
    void shouldFlushOnAppend_trueWhenAtThreshold() {
        var policy = new FlushTriggerPolicy(5, 60000);
        assertTrue(policy.shouldFlushOnAppend(5));
    }

    @Test
    void shouldFlushOnAppend_falseWhenBelowThreshold() {
        var policy = new FlushTriggerPolicy(5, 60000);
        assertFalse(policy.shouldFlushOnAppend(4));
    }

    @Test
    void shouldFlushOnAppend_falseWhenZeroMaxEvents() {
        var policy = new FlushTriggerPolicy(0, 60000);
        assertFalse(policy.shouldFlushOnAppend(100));
    }

    @Test
    void shouldFlushOnIdle_trueWhenIdleExceeded() {
        var policy = new FlushTriggerPolicy(5, 30000);
        assertTrue(policy.shouldFlushOnIdle(1000L, 31001L, true));
    }

    @Test
    void shouldFlushOnIdle_falseWhenNotIdleEnough() {
        var policy = new FlushTriggerPolicy(5, 30000);
        assertFalse(policy.shouldFlushOnIdle(1000L, 20000L, true));
    }

    @Test
    void shouldFlushOnIdle_falseWhenNoEvents() {
        var policy = new FlushTriggerPolicy(5, 30000);
        assertFalse(policy.shouldFlushOnIdle(1000L, 100000L, false));
    }

    @Test
    void shouldFlushOnIdle_falseWhenIdleZero() {
        var policy = new FlushTriggerPolicy(5, 0);
        assertFalse(policy.shouldFlushOnIdle(1000L, 100000L, true));
    }
}
