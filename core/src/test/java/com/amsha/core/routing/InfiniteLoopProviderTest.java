package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
}
