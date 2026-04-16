package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;

class StatefulAgentStoreRetryTest {

    private final SqliteCheckpointStoreAdapter adapter = createAdapter();

    private SqliteCheckpointStoreAdapter createAdapter() {
        SqliteCheckpointStoreAdapter a = new SqliteCheckpointStoreAdapter();
        try {
            var field = SqliteCheckpointStoreAdapter.class.getDeclaredField("retryTemplate");
            field.setAccessible(true);
            field.set(a, new SqliteRetryTemplate());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return a;
    }

    @Test
    void testExponentialBackoffDelayIncreases() throws Exception {
        Method withRetry = SqliteCheckpointStoreAdapter.class.getDeclaredMethod("withRetry", Callable.class, String.class);
        withRetry.setAccessible(true);

        AtomicInteger attempts = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        // Fail 2 times, succeed on 3rd
        withRetry.invoke(adapter, (Callable<Object>) () -> {
            if (attempts.incrementAndGet() <= 2) {
                // SQLite busy typically surfaces as LockAcquisitionException in Hibernate
                throw new LockAcquisitionException("busy", new SQLException("database is locked"), "operation");
            }
            return "success";
        }, "test-op");

        long elapsed = System.currentTimeMillis() - start;

        // With BACKOFF_BASE_MS = 50 in SqliteRetryTemplate:
        // Attempt 1: fail -> sleep 50 * 2^1 = 100ms
        // Attempt 2: fail -> sleep 50 * 2^2 = 200ms
        // Total expected 300ms, but Jitter (-25%) can reduce it to 225ms.
        assertTrue(elapsed >= 225, "Total elapsed time should be at least 225ms (jittered), but was " + elapsed);
    }

    @Test
    void testIsBusyDetection() {
        // Now using central utility
        Exception ex = new RuntimeException(new LockAcquisitionException("busy", new SQLException("database is locked"), "SELECT 1"));
        assertTrue(SqliteErrorStrings.isBusy(ex), "Should detect LockAcquisitionException as SQLite busy");

        Exception unrelated = new RuntimeException("connection reset");
        assertFalse(SqliteErrorStrings.isBusy(unrelated), "Should NOT detect 'connection reset' as SQLite busy");
    }
}
