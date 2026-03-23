package com.arcclaw.core.scope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.arcclaw.core.security.AutonomyLevel;
import org.junit.jupiter.api.Test;

class WorkspaceContextTest {

    @Test
    void testFullAutonomyScopedToSingleThread() throws InterruptedException {
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<AutonomyLevel> thread1Level = new AtomicReference<>();
        AtomicReference<AutonomyLevel> thread2Level = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            WorkspaceContext.runWithFullAutonomy("sess-1", "user-1", "chat-1", "coordinator", () -> {
                thread1Level.set(WorkspaceContext.autonomy());
                latch1.countDown();
                try { latch2.await(); } catch (InterruptedException _) {}
            });
        });

        Thread t2 = new Thread(() -> {
            try { latch1.await(); } catch (InterruptedException _) {}
            WorkspaceContext.run("sess-2", "user-2", "chat-2", AutonomyLevel.READONLY, "researcher", () -> {
                thread2Level.set(WorkspaceContext.autonomy());
                latch2.countDown();
            });
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(AutonomyLevel.FULL, thread1Level.get());
        assertEquals(AutonomyLevel.READONLY, thread2Level.get());
    }

    @Test
    void testSessionIdRequiredThrowsIfUnbound() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, WorkspaceContext::requireSessionId);
        assertTrue(ex.getMessage().contains("unbound"));
    }

    @Test
    void testNestedScopesDoNotLeak() {
        WorkspaceContext.run("sess-outer", "user-1", "chat-1", AutonomyLevel.SUPERVISED, "coordinator", () -> {
            assertEquals("sess-outer", WorkspaceContext.requireSessionId());
            assertEquals(AutonomyLevel.SUPERVISED, WorkspaceContext.autonomy());

            WorkspaceContext.runWithFullAutonomy("sess-inner", "user-2", "chat-2", "coder", () -> {
                assertEquals("sess-inner", WorkspaceContext.requireSessionId());
                assertEquals(AutonomyLevel.FULL, WorkspaceContext.autonomy());
                assertEquals("user-2", WorkspaceContext.userId());
            });

            // Outer restored
            assertEquals("sess-outer", WorkspaceContext.requireSessionId());
            assertEquals(AutonomyLevel.SUPERVISED, WorkspaceContext.autonomy());
            assertEquals("user-1", WorkspaceContext.userId());
        });
    }
}
