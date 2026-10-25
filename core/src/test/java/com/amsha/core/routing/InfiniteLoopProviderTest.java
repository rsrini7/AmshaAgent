package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.llm.api.error.LlmAuthenticationException;
import com.amsha.llm.api.error.LlmMalformedResponseException;
import com.amsha.llm.api.error.LlmServiceUnavailableException;

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
    void slowValidResponseMultiSlotCoolsDownSlowSlot() {
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                "multi-slot-test",
                List.of(
                        new InfiniteLoopProvider.Slot<>("slot-1", "m1"),
                        new InfiniteLoopProvider.Slot<>("slot-2", "m2")),
                (model, request) -> {
                    if ("m2".equals(model)) {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        return "from-slot-2";
                    }
                    return "from-slot-1";
                },
                10,
                10,
                10,
                1);

        // First call starts at index (0 + 1) % 2 = 1 (slot-2, slow).
        // It returns valid response and cools down slot-2.
        assertEquals("from-slot-2", provider.invoke("request"));
        assertEquals(
                "slot-2",
                InfiniteLoopProvider.getLastUsedSlot("multi-slot-test").orElse(null));

        // Second call starts at index 0 (slot-1, fast).
        assertEquals("from-slot-1", provider.invoke("request"));
        assertEquals(
                "slot-1",
                InfiniteLoopProvider.getLastUsedSlot("multi-slot-test").orElse(null));

        // Third call wants to start at index 1 (slot-2), but slot-2 is cooled down, so it falls back to slot-1.
        assertEquals("from-slot-1", provider.invoke("request"));
        assertEquals(
                "slot-1",
                InfiniteLoopProvider.getLastUsedSlot("multi-slot-test").orElse(null));
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
    void repeatedConnectionFailuresStopWithSafeMessage() {
        AtomicInteger attempts = new AtomicInteger();
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                List.of(new InfiniteLoopProvider.Slot<>("local", "model")),
                (model, request) -> {
                    if (attempts.incrementAndGet() <= 4) {
                        throw new RuntimeException("Connection refused at http://127.0.0.1:1234");
                    }
                    return "unexpected recovery";
                },
                1,
                1,
                1,
                0);

        LlmServiceUnavailableException failure =
                assertThrows(LlmServiceUnavailableException.class, () -> provider.invoke("request"));

        assertEquals(4, attempts.get());
        assertEquals(
                "I couldn't reach the AI service after several attempts. Please try again shortly.",
                failure.getMessage());
        assertFalse(failure.getMessage().contains("127.0.0.1"));
    }

    @Test
    void testRecordQualityFailureAndStats() {
        InfiniteLoopProvider<String, String> _ = new InfiniteLoopProvider<>(
                "test-loop",
                List.of(new InfiniteLoopProvider.Slot<>("slot-1", "model")),
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

    @Test
    void testMalformedResponseAdvancesToNextSlot() {
        AtomicInteger slot1Calls = new AtomicInteger();
        AtomicInteger slot2Calls = new AtomicInteger();

        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                "malformed-loop",
                List.of(
                        new InfiniteLoopProvider.Slot<>("slot-1", "m1"),
                        new InfiniteLoopProvider.Slot<>("slot-2", "m2")),
                (model, request) -> {
                    if ("m2".equals(model)) {
                        slot2Calls.incrementAndGet();
                        throw new LlmMalformedResponseException("schema rejected");
                    }
                    slot1Calls.incrementAndGet();
                    return "slot-1-response";
                },
                1,
                1,
                1,
                0);

        String result = provider.invoke("test-req");
        assertEquals("slot-1-response", result);
        assertEquals(1, slot2Calls.get());
        assertEquals(1, slot1Calls.get());
    }

    @Test
    void testMalformedResponsesAreBoundedWhenAllSlotsReject() {
        AtomicInteger attempts = new AtomicInteger();
        InfiniteLoopProvider<String, String> provider = new InfiniteLoopProvider<>(
                "bounded-malformed-loop",
                List.of(
                        new InfiniteLoopProvider.Slot<>("slot-1", "model-1"),
                        new InfiniteLoopProvider.Slot<>("slot-2", "model-2")),
                (model, request) -> {
                    attempts.incrementAndGet();
                    throw new LlmMalformedResponseException("schema rejected");
                },
                0,
                0,
                0,
                0);

        assertThrows(LlmMalformedResponseException.class, () -> provider.invoke("test-req"));
        assertEquals(
                2,
                attempts.get(),
                "Malformed responses may fail over once but must not trigger an unbounded retry loop");
    }
}
