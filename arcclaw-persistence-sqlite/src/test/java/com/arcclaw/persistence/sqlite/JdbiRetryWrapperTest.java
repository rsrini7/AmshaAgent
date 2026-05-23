package com.arcclaw.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jdbi.v3.core.transaction.TransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbiRetryWrapperTest {

    private JdbiRetryWrapper wrapper;

    @BeforeEach
    void setUp() {
        wrapper = new JdbiRetryWrapper();
    }

    @Test
    void succeedsOnFirstTry() {
        String result = wrapper.withRetry("test", 3, 10, () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    void retriesThenSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = wrapper.withRetry("test", 5, 10, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new UnableToExecuteStatementException(
                    new RuntimeException("SQLITE_BUSY: database is locked"), null);
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void exhaustsAllAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () ->
            wrapper.withRetry("test", 3, 10, () -> {
                attempts.incrementAndGet();
                throw new UnableToExecuteStatementException(
                    new RuntimeException("SQLITE_BUSY: database is locked"), null);
            })
        );
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryNonBusyException() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () ->
            wrapper.withRetry("test", 3, 10, () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("some other error");
            })
        );
        assertEquals(1, attempts.get());
    }

    @Test
    void retriesTransactionException() {
        AtomicInteger attempts = new AtomicInteger();
        String result = wrapper.withRetry("test", 5, 10, () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new TransactionException(
                    new RuntimeException("SQLITE_BUSY: database is locked"));
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void handlesCauseChainWithBusyDeeplyNested() {
        AtomicInteger attempts = new AtomicInteger();
        String result = wrapper.withRetry("test", 5, 10, () -> {
            if (attempts.incrementAndGet() < 2) {
                Exception root = new RuntimeException("database is locked");
                Exception mid = new RuntimeException("mid", root);
                throw new UnableToExecuteStatementException(mid, null);
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void voidRetryExhaustsOnBusy() {
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(RuntimeException.class, () ->
            wrapper.withRetryVoid("test", 2, 10, () -> {
                attempts.incrementAndGet();
                throw new UnableToExecuteStatementException(
                    new RuntimeException("SQLITE_BUSY: database is locked"), null);
            })
        );
        assertEquals(2, attempts.get());
    }
}
