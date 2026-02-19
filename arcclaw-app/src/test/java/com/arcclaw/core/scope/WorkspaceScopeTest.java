package com.arcclaw.core.scope;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link WorkspaceScoped} CDI scope behavior:
 * <ul>
 * <li>Same sessionId → same bean instance across parallel threads</li>
 * <li>Different sessionId → different bean instances</li>
 * <li>No sessionId → {@link ContextNotActiveException}</li>
 * </ul>
 */
@QuarkusTest
class WorkspaceScopeTest {

    @Inject
    ScopedCounter counter;

    @Inject
    WorkspaceScopeManager scopeManager;

    @AfterEach
    void cleanup() {
        scopeManager.clear();
    }

    @Test
    void sameSession_sameInstance_acrossParallelThreads() throws Exception {
        final String sessionId = "test-session-001";
        final int threadCount = 4;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final CountDownLatch done = new CountDownLatch(threadCount);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        // All threads increment the same @WorkspaceScoped counter
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().name("ws-test-", i).start(() -> {
                try {
                    scopeManager.setSessionId(sessionId);
                    barrier.await(); // synchronize start
                    counter.increment();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    scopeManager.clear();
                    done.countDown();
                }
            });
        }

        done.await();
        assertNull(failure.get(), () -> "Thread failed: " + failure.get());

        // Verify: all increments hit the same instance
        scopeManager.setSessionId(sessionId);
        try {
            assertEquals(threadCount, counter.getCount(),
                    "All threads should share the same @WorkspaceScoped bean instance");
        } finally {
            scopeManager.clear();
        }
    }

    @Test
    void differentSessions_differentInstances() {
        final String sessionA = "session-A";
        final String sessionB = "session-B";

        // Increment in session A
        scopeManager.setSessionId(sessionA);
        counter.increment();
        counter.increment();
        scopeManager.clear();

        // Increment in session B
        scopeManager.setSessionId(sessionB);
        counter.increment();
        int countB = counter.getCount();
        scopeManager.clear();

        // Session B should have its own counter starting from 0
        assertEquals(1, countB,
                "Different sessions must have isolated @WorkspaceScoped bean instances");

        // Session A should still have count 2
        scopeManager.setSessionId(sessionA);
        assertEquals(2, counter.getCount());
        scopeManager.clear();
    }

    @Test
    void noSession_throwsContextNotActive() {
        // No set() — scope is inactive
        scopeManager.clear();
        assertThrows(ContextNotActiveException.class, () -> counter.increment(),
                "Accessing @WorkspaceScoped bean without active session should throw ContextNotActiveException");
    }
}
