package com.arcclaw.app.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SessionRegistryTest {

    @Inject
    SessionRegistry registry;

    @Test
    void testNewSessionCreatesRandomUuid() {
        String sessionId = registry.resolveOrCreate("TELEGRAM", "chat-new-1", "user-new-1");
        assertNotNull(sessionId);
        assertFalse(sessionId.isBlank());
        // Must be a valid UUID
        assertDoesNotThrow(() -> UUID.fromString(sessionId));
    }

    @Test
    void testSameInputReturnsSameSessionId() {
        String id1 = registry.resolveOrCreate("TELEGRAM", "chat-stable", "user-stable");
        String id2 = registry.resolveOrCreate("TELEGRAM", "chat-stable", "user-stable");
        assertEquals(id1, id2, "Same triplet must always return the same session ID");
    }

    @Test
    void testDifferentChannelTypeProducesDifferentSession() {
        String telegramId = registry.resolveOrCreate("TELEGRAM", "chat-mt", "user-mt");
        String cliId      = registry.resolveOrCreate("CLI",      "chat-mt", "user-mt");
        assertNotEquals(telegramId, cliId, "Different channel types must produce different session IDs");
    }

    @Test
    void testConcurrentCreateDoesNotDuplicateSession() throws Exception {
        String chatId  = "chat-concurrent-" + System.currentTimeMillis();
        String userId  = "user-concurrent";
        int    count   = 5;

        List<String> results = IntStream.range(0, count)
            .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                registry.resolveOrCreate("TELEGRAM", chatId, userId)))
            .toList()
            .stream()
            .map(f -> {
                try { return f.get(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new RuntimeException(e); }
            })
            .toList();

        // All 5 must return the same session ID
        String expected = results.get(0);
        results.forEach(id -> assertEquals(expected, id,
            "All concurrent calls must return the same session ID"));
    }
}
