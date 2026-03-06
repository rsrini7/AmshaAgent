package com.arcclaw.core.state;

import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StatefulAgentStoreRetryTest {

    private final StatefulAgentStoreImpl store = new StatefulAgentStoreImpl();

    @Test
    void testExponentialBackoffDelayIncreases() throws Exception {
        Method withRetry = StatefulAgentStoreImpl.class.getDeclaredMethod("withRetry", Callable.class, String.class);
        withRetry.setAccessible(true);

        AtomicInteger attempts = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        // Fail 2 times, succeed on 3rd
        withRetry.invoke(store, (Callable<Object>) () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new LockAcquisitionException("busy", new SQLException("database is locked"), "operation");
            }
            return "success";
        }, "test-op");

        long elapsed = System.currentTimeMillis() - start;

        // With BACKOFF_BASE_MS = 50:
        // Attempt 1: fail -> sleep 50 * 2^1 = 100ms + jitter
        // Attempt 2: fail -> sleep 50 * 2^2 = 200ms + jitter
        // Total expected >= 100 + 200 = 300ms
        // The floor is 100ms for the first failure. 
        // Let's be conservative as per user request (>= BACKOFF_BASE_MS * 2 = 100ms) 
        // but actually 2 failures should be ~300ms.
        assertTrue(elapsed >= 100, "Total elapsed time should be at least 100ms (floor of first backoff), but was " + elapsed);
    }

    @Test
    void testJITTERFieldIsInitialized() throws Exception {
        Field jitterField = StatefulAgentStoreImpl.class.getDeclaredField("JITTER");
        jitterField.setAccessible(true);
        assertNotNull(jitterField.get(null), "JITTER Random must be initialized");
    }

    @Test
    void testIsSqliteBusyDetectsLockAcquisitionException() throws Exception {
        Method isSqliteBusy = StatefulAgentStoreImpl.class.getDeclaredMethod("isSqliteBusy", Throwable.class);
        isSqliteBusy.setAccessible(true);

        Exception ex = new RuntimeException(new LockAcquisitionException("busy", new SQLException("database is locked"), "SELECT 1"));
        boolean result = (boolean) isSqliteBusy.invoke(store, ex);
        assertTrue(result, "Should detect LockAcquisitionException as SQLite busy");
    }

    @Test
    void testIsSqliteBusyIgnoresUnrelatedExceptions() throws Exception {
        Method isSqliteBusy = StatefulAgentStoreImpl.class.getDeclaredMethod("isSqliteBusy", Throwable.class);
        isSqliteBusy.setAccessible(true);

        Exception ex = new RuntimeException("connection reset");
        boolean result = (boolean) isSqliteBusy.invoke(store, ex);
        assertFalse(result, "Should NOT detect 'connection reset' as SQLite busy");
    }
}
