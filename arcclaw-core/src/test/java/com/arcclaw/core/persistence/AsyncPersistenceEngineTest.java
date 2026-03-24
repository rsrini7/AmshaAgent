package com.arcclaw.core.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import com.arcclaw.core.audit.AuditHashUtil;
import com.arcclaw.core.audit.ComplianceViolationException;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link AsyncPersistenceEngine}.
 * Uses @QuarkusTest to provide the necessary CDI and Transactional context.
 */
@QuarkusTest
class AsyncPersistenceEngineTest {

    @InjectMock
    EntityManager em;

    @Inject
    AsyncPersistenceEngine engine;

    @Inject
    MeterRegistry registry;

    @AfterEach
    void cleanUp() {
        engine.clearForTest();
    }

    // -------------------------------------------------------------------------

    @Test
    void testCriticalEventBlocksUntilWritten() {
        AtomicBoolean written = new AtomicBoolean(false);

        PersistenceEvent event = PersistenceEvent.critical("ses-1", SecurityEventType.TOOL_INVOKED,
                em2 -> {
                    written.set(true);
                });

        // submitCritical blocks until the consumer completes the future
        engine.submitCritical(event);

        assertTrue(written.get());
    }

    @Test
    void testTelemetryEventIsFireAndForget() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        PersistenceEvent event = PersistenceEvent.telemetry("ses-2", SecurityEventType.REFLEX_COMMAND,
                em2 -> latch.countDown());

        engine.submitTelemetry(event);

        // We do NOT block — just assert the consumer runs it eventually
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testSqliteBusyRetryExhaustedThrowsCompliance() {
        AtomicInteger attempts = new AtomicInteger(0);
        RuntimeException busyEx = new RuntimeException("SQLITE_BUSY: database is locked");

        PersistenceEvent event = PersistenceEvent.critical("ses-3", SecurityEventType.VAULT_ACCESS,
                em2 -> {
                    attempts.incrementAndGet();
                    throw busyEx;
                });

        ComplianceViolationException ex = assertThrows(ComplianceViolationException.class, () -> engine.submitCritical(event));
        assertTrue(ex.getMessage().contains("ses-3"));

        // Should have attempted MAX_RETRIES times
        assertEquals(5, attempts.get());
    }

    @Test
    void testSqliteBusyRetryExhaustedDiscardsWithWarnForTelemetry() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(5);
        RuntimeException busyEx = new RuntimeException("database is locked");

        PersistenceEvent event = PersistenceEvent.telemetry("ses-4", SecurityEventType.CONTEXT_COMPACTED,
                em2 -> {
                    latch.countDown();
                    throw busyEx;
                });

        engine.submitTelemetry(event);

        // All 5 attempts happen (fire-and-forget, no exception thrown to the test thread)
        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    void testPerEventScopeBinding() throws InterruptedException {
        List<String> captured = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        PersistenceEvent e1 = PersistenceEvent.telemetry("session-AAA", SecurityEventType.LOOP_FINISH,
                em2 -> {
                    captured.add(WorkspaceScopeHolder.getIfActive());
                    latch.countDown();
                });
        PersistenceEvent e2 = PersistenceEvent.telemetry("session-BBB", SecurityEventType.LOOP_FINISH,
                em2 -> {
                    captured.add(WorkspaceScopeHolder.getIfActive());
                    latch.countDown();
                });

        engine.submitTelemetry(e1);
        engine.submitTelemetry(e2);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Each event must see its own session, not bleed across
        assertTrue(captured.contains("session-AAA"));
        assertTrue(captured.contains("session-BBB"));
        assertEquals(2, captured.size());
    }

    @Test
    void testIsSqliteBusyDetectsWrappedCause() {
        // The engine must traverse getCause() chain — not just top-level message
        RuntimeException outer = new RuntimeException("Transaction rollback",
                new RuntimeException("JPA error",
                        new RuntimeException("SQLITE_BUSY: database is locked")));

        assertTrue(AsyncPersistenceEngine.isSqliteBusy(outer));
    }

    @Test
    void testCriticalQueueFullPersistsSynchronously() throws Exception {
        CountDownLatch pauseConsumer = new CountDownLatch(1);
        
        // 1. Submit the blocking event on a background thread
        ExecutorService exec = Executors.newFixedThreadPool(550);
        
        exec.submit(() -> {
            engine.submitCritical(PersistenceEvent.critical("blocker", SecurityEventType.TOOL_INVOKED, em -> {
                try { pauseConsumer.await(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }));
        });
        
        // Give it a tiny moment to pick it up and block the consumer thread
        Thread.sleep(100);
        
        // 2. Submit 500 items to fill the critical queue
        for(int i = 0; i < 500; i++) {
            exec.submit(() -> {
                engine.submitCritical(PersistenceEvent.critical("filler", SecurityEventType.TOOL_INVOKED, em -> {}));
            });
        }
        
        // Wait for them to hit the queue
        Thread.sleep(200);
        
        // 3. Submit the 501st from the main test thread
        // The queue is full (size 500 + 1 processing). This should NOT queue, it should persist synchronously on THIS thread.
        String expectedThreadName = Thread.currentThread().getName();
        AtomicBoolean ranSynchronously = new AtomicBoolean(false);
        
        engine.submitCritical(PersistenceEvent.critical("overflow", SecurityEventType.TOOL_INVOKED, em -> {
            if (Thread.currentThread().getName().equals(expectedThreadName)) {
                ranSynchronously.set(true);
            }
        }));
        
        assertTrue(ranSynchronously.get(), "501st event (mandatory) should never be dropped and must run synchronously on the test thread under pressure");
        
        // Cleanup
        pauseConsumer.countDown();
        exec.shutdownNow();
    }

    @Test
    void testTelemetryQueueFullDropsEvent() throws Exception {
        CountDownLatch pauseConsumer = new CountDownLatch(1);
        
        // 1. Submit blocker (since it's telemetry, caller does not block)
        engine.submitTelemetry(PersistenceEvent.telemetry("blocker", SecurityEventType.TOOL_INVOKED, em -> {
            try { pauseConsumer.await(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }));
        
        Thread.sleep(100);
        
        // 2. Fill queue (capacity 5000)
        for (int i = 0; i < 5000; i++) {
            engine.submitTelemetry(PersistenceEvent.telemetry("filler", SecurityEventType.TOOL_INVOKED, em -> {}));
        }
        
        // 3. Submit 5001st event
        double countBefore = registry.counter("arcclaw.audit.queue.overflow", "tier", "telemetry").count();
        
        engine.submitTelemetry(PersistenceEvent.telemetry("dropped", SecurityEventType.TOOL_INVOKED, em -> {}));
        
        double countAfter = registry.counter("arcclaw.audit.queue.overflow", "tier", "telemetry").count();
        assertEquals(countBefore + 1.0, countAfter, "Telemetry overflow counter should be incremented when dropping the 5001st event");
        
        pauseConsumer.countDown();
    }

    @Test
    void testDrainThreadsAreStartedWithExpectedNames() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean criticalFound = new AtomicBoolean(false);
        AtomicBoolean telemetryFound = new AtomicBoolean(false);

        engine.submitCritical(PersistenceEvent.critical("tname-1", SecurityEventType.TOOL_INVOKED, em -> {
            if ("arcclaw-audit-critical-drain".equals(Thread.currentThread().getName())) {
                criticalFound.set(true);
            }
            latch.countDown();
        }));

        engine.submitTelemetry(PersistenceEvent.telemetry("tname-2", SecurityEventType.LOOP_FINISH, em -> {
            if ("arcclaw-audit-telemetry-drain".equals(Thread.currentThread().getName())) {
                telemetryFound.set(true);
            }
            latch.countDown();
        }));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(criticalFound.get(), "Critical drain thread not found");
        assertTrue(telemetryFound.get(), "Telemetry drain thread not found");
    }

    @InjectMock
    AuditHashUtil auditHashUtil;

    @Test
    void drainThread_SurvivesHmacFailure_WhenKeyBecomesAvailableOnSecondAttempt()
            throws Exception {
        // First call throws HMAC unavailable, second succeeds
        Mockito.doThrow(new IllegalStateException("Audit HMAC key unavailable"))
            .doReturn("dummy-hmac")
            .when(auditHashUtil).hmacHex(anyString());

        CountDownLatch latch = new CountDownLatch(1);
        PersistenceEvent event = PersistenceEvent.critical("ses-retry", SecurityEventType.TOOL_INVOKED,
                em2 -> {
                    auditHashUtil.hmacHex("test");
                    latch.countDown();
                });

        engine.submitCritical(event);

        // Give drain thread time to retry and succeed
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Event must be persisted after retry succeeds");
    }

    @Test
    void drainThread_ContinuesDraining_AfterExhaustingHmacRetries() throws Exception {
        // All retries fail for first event
        Mockito.doThrow(new IllegalStateException("Audit HMAC key unavailable"))
            .when(auditHashUtil).hmacHex(anyString());

        CountDownLatch latch = new CountDownLatch(1);
        PersistenceEvent failingEvent = PersistenceEvent.critical("ses-fail", SecurityEventType.TOOL_INVOKED,
                em2 -> {
                    auditHashUtil.hmacHex("test");
                });
        
        PersistenceEvent nextEvent = PersistenceEvent.critical("ses-next", SecurityEventType.TOOL_INVOKED,
                em2 -> {
                    auditHashUtil.hmacHex("test");
                    latch.countDown();
                });

        // First event fails and throws after 3 retries (total 4 attempts).
        // Since submitCritical blocks until the background thread completes the future,
        // it should directly throw ComplianceViolationException (translated from CompletionException).
        assertThrows(ComplianceViolationException.class, () -> engine.submitCritical(failingEvent));
        
        // Second event should still be processed if the drain thread is alive
        // We need to reset the mock to succeed for the second event
        Mockito.doReturn("dummy-hmac").when(auditHashUtil).hmacHex(anyString());
        
        engine.submitCritical(nextEvent);

        assertTrue(latch.await(5, TimeUnit.SECONDS), 
            "Second event must be persisted — drain thread must not die after first event failure");
        
        // Wait up to 1s for the counter to increment in the drain thread (avoid race)
        long start = System.currentTimeMillis();
        while (registry.counter("arcclaw.audit.drain.hmac_failure").count() < 1 && (System.currentTimeMillis() - start) < 1000) {
            Thread.sleep(10);
        }

        assertEquals(1, (int) registry.counter("arcclaw.audit.drain.hmac_failure").count(), 
            "Failure counter must be incremented once");
    }

    @Test
    void testDrainThreadsAreDaemon() {
        // Virtual threads are always daemon in Java 21+. 
        // This test verifies that no non-daemon threads with the signature name are alive.
        boolean anyNonDaemonAuditThread = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("arcclaw-audit"))
                .anyMatch(t -> !t.isDaemon());
        
        assertFalse(anyNonDaemonAuditThread, "Found non-daemon arcclaw-audit threads — all drain threads must be daemon to allow clean JVM exit");
    }
}
