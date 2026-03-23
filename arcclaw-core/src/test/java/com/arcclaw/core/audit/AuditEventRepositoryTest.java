package com.arcclaw.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class AuditEventRepositoryTest {

    @Inject
    AuditEventRepository repository;

    @Inject
    AuditHashUtil hashUtil;

    @Test
    @Transactional
    public void testRecordAndFindSession() {
        String sessionId = "test-session-" + System.currentTimeMillis();
        AuditEvent event = AuditEvent.of(sessionId, "user1", "coordinator", "hash1",
                AuditOutcome.FINISH, SecurityEventType.LOOP_FINISH, 100, 200, BigDecimal.valueOf(0.01), 1, null);
        
        repository.record(event);

        List<AuditEvent> found = repository.findBySessionId(sessionId);
        assertEquals(1, found.size());
        assertEquals("user1", found.get(0).userId);
    }

    @Test
    @Transactional
    public void testHmacChainGeneration() {
        // Use timestamp-based unique ID — never reuses across test runs
        String sessionId = "test-hmac-chain-" + System.currentTimeMillis();
        
        AuditEvent e1 = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "t1");
        repository.record(e1);
        
        AuditEvent e2 = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "t2");
        repository.record(e2);
        
        AuditEvent e3 = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "t3");
        repository.record(e3);

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(3, events.size());

        // e1 should link to genesis
        String expectedGenesis = hashUtil.sha256hex("GENESIS:" + sessionId);
        assertEquals(expectedGenesis, events.get(0).prevHash);
        
        // e2 should link to e1
        assertEquals(events.get(0).hmacHash, events.get(1).prevHash);
        
        // e3 should link to e2
        assertEquals(events.get(1).hmacHash, events.get(2).prevHash);
    }

    @Test
    public void testConcurrentAuditWritesSameSessionProduceUniqueSequences() throws Exception {
        String sessionId = "concurrent-session-" + System.currentTimeMillis();
        int count = 20;
        CountDownLatch latch = new CountDownLatch(count);
        
        CompletableFuture<?>[] futures = IntStream.range(0, count)
            .mapToObj(i -> CompletableFuture.runAsync(() -> {
                try {
                    QuarkusTransaction.requiringNew().run(() -> {
                        AuditEvent event = AuditEvent.securityEvent(sessionId, "u1", "r1", SecurityEventType.TOOL_INVOKED, "tool-" + i);
                        repository.record(event);
                    });
                } finally {
                    latch.countDown();
                }
            }))
            .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        List<AuditEvent> events = repository.findBySessionId(sessionId);
        assertEquals(count, events.size());

        // Verify uniqueness of sequence
        long uniqueSeqs = events.stream().map(e -> e.sequence).distinct().count();
        assertEquals(count, uniqueSeqs, "Sequences must be unique across concurrent writes");

        // Verify unbroken HMAC chain
        String expectedPrev = hashUtil.sha256hex("GENESIS:" + sessionId);
        for (AuditEvent event : events) {
            assertEquals(expectedPrev, event.prevHash, "Chain broken at sequence " + event.sequence);
            expectedPrev = event.hmacHash;
        }
    }

    @Test
    public void testDifferentSessionsDoNotBlockEachOther() throws Exception {
        String sidA = "session-a-" + System.currentTimeMillis();
        String sidB = "session-b-" + System.currentTimeMillis();
        int count = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(count * 2);

        Runnable taskA = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    QuarkusTransaction.requiringNew().run(() -> {
                        repository.record(AuditEvent.securityEvent(sidA, "u1", "r1", SecurityEventType.TOOL_INVOKED, "a-" + index));
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                for (int i = 0; i < count; i++) finishLatch.countDown();
            }
        };

        Runnable taskB = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < count; i++) {
                    final int index = i;
                    QuarkusTransaction.requiringNew().run(() -> {
                        repository.record(AuditEvent.securityEvent(sidB, "u1", "r1", SecurityEventType.TOOL_INVOKED, "b-" + index));
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                for (int i = 0; i < count; i++) finishLatch.countDown();
            }
        };

        CompletableFuture.runAsync(taskA);
        CompletableFuture.runAsync(taskB);

        startLatch.countDown();
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "Concurrent sessions blocked each other or timed out");
        
        assertEquals(count, repository.findBySessionId(sidA).size());
        assertEquals(count, repository.findBySessionId(sidB).size());
    }
}
