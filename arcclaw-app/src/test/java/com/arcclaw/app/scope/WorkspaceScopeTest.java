package com.arcclaw.app.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import com.arcclaw.core.scope.ScopedCounter;
import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.scope.WorkspaceScoped;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

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
                    WorkspaceScopeHolder.run(sessionId, () -> {
                        try {
                            barrier.await(); // synchronize start
                            counter.increment();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        done.await();
        assertNull(failure.get(), () -> "Thread failed: " + failure.get());

        // Verify: all increments hit the same instance
        WorkspaceScopeHolder.run(sessionId, () -> {
            assertEquals(
                    threadCount,
                    counter.getCount(),
                    "All threads should share the same @WorkspaceScoped bean instance");
        });
    }

    @Test
    void differentSessions_differentInstances() throws Exception {
        final String sessionA = "session-A";
        final String sessionB = "session-B";

        // Increment in session A
        WorkspaceScopeHolder.run(sessionA, () -> {
            counter.increment();
            counter.increment();
        });

        // Increment in session B
        int countB = WorkspaceScopeHolder.call(sessionB, () -> {
            counter.increment();
            return counter.getCount();
        });

        // Session B should have its own counter starting from 0
        assertEquals(1, countB, "Different sessions must have isolated @WorkspaceScoped bean instances");

        // Session A should still have count 2
        WorkspaceScopeHolder.run(sessionA, () -> {
            assertEquals(2, counter.getCount());
        });
    }

    @Test
    void noSession_throwsContextNotActive() {
        // No WorkspaceScopeHolder.run/call - scope is inactive
        assertThrows(
                ContextNotActiveException.class,
                () -> counter.increment(),
                "Accessing @WorkspaceScoped bean without active session should throw ContextNotActiveException");
    }
}
