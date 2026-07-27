package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.agent.reasoning.error.LlmAuthenticationException;

import org.junit.jupiter.api.Test;

class InfiniteLoopProviderTest {

    @Test
    void slowValidResponseIsReturnedInsteadOfDiscarded() {
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                List.of(new InfiniteLoopProvider.Slot<>("local", "model")),
                (model, request) -> {
                    Thread.sleep(5);
                    return "valid";
                },
                1,
                1,
                1,
                1);

        assertEquals("valid", provider.invoke("request"));
        assertEquals("local", InfiniteLoopProvider.getLastUsedSlot().orElse(null));
    }

    @Test
    void transientFailuresRemainRecoverableAfterThreshold() {
        AtomicInteger attempts = new AtomicInteger();
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                List.of(new InfiniteLoopProvider.Slot<>("local", "model")),
                (model, request) -> {
                    if (attempts.incrementAndGet() <= 3) {
                        throw new RuntimeException("Connection refused");
                    }
                    return "recovered";
                },
                1,
                1,
                1,
                0);

        assertEquals("recovered", provider.invoke("request"));
    }

    @Test
    void testRecordQualityFailureAndStats() {
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                "test-loop",
                List.of(new InfiniteLoopProvider.Slot<>("slot-1", "model-1")),
                (model, request) -> "ok",
                1,
                1,
                1,
                0);

        InfiniteLoopProvider.recordQualityFailure("test-loop", "slot-1");
        InfiniteLoopProvider.recordQualityFailure("test-loop", "slot-1");
        InfiniteLoopProvider.recordQualityFailure("test-loop", "slot-1");

        String stats = InfiniteLoopProvider.getSlotStats();
        assertNotNull(stats);
        assertTrue(stats.contains("test-loop:slot-1"));
    }

    @Test
    void testAuthErrorPermanentlyDisablesSlot() {
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                "auth-loop",
                List.of(new InfiniteLoopProvider.Slot<>("auth-slot", "model")),
                (model, request) -> {
                    throw new LlmAuthenticationException("Invalid API Key");
                },
                1,
                1,
                1,
                0);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> provider.invoke("req"));
        assertTrue(ex.getMessage().contains("permanently disabled"));
    }
}
