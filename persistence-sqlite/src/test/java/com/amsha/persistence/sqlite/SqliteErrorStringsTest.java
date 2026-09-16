package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class SqliteErrorStringsTest {

    /**
     * Named so that the <em>canonical class name</em> carries the marker the detector matches on. The
     * detector deliberately checks the type name rather than {@code instanceof} because the real JPA
     * exception classes are not on this module's compile classpath.
     */
    private static final class LockAcquisitionException extends RuntimeException {
        LockAcquisitionException() {
            super("could not acquire lock");
        }
    }

    /** Same trick for the optimistic-locking marker. */
    private static final class OptimisticLockException extends RuntimeException {
        OptimisticLockException() {
            super("row was updated by another transaction");
        }
    }

    @Test
    void testIsBusyWithBusyMessage() {
        Throwable t = new RuntimeException("SQLITE_BUSY: database is locked");
        assertTrue(SqliteErrorStrings.isBusy(t));
    }

    @Test
    void testIsBusyWithCauseChain() {
        Throwable root = new RuntimeException("database is locked");
        Throwable mid = new RuntimeException("wrapped", root);
        Throwable top = new RuntimeException("top", mid);

        assertTrue(SqliteErrorStrings.isBusy(top));
    }

    @Test
    void testIsBusyWithUnrelatedMessage() {
        Throwable t = new RuntimeException("Some other error");
        assertFalse(SqliteErrorStrings.isBusy(t));
    }

    @Test
    void testIsBusyWithNull() {
        assertFalse(SqliteErrorStrings.isBusy(null));
    }

    @Test
    void testIsBusyWithLockAcquisitionExceptionTypeName() {
        assertTrue(SqliteErrorStrings.isBusy(new LockAcquisitionException()));
    }

    @Test
    void testIsBusyWithOptimisticLockExceptionTypeName() {
        assertTrue(SqliteErrorStrings.isBusy(new OptimisticLockException()));
    }

    @Test
    void testIsBusyWithLockAcquisitionExceptionNestedInTheCauseChain() {
        // The marker may sit anywhere in the chain, not only at the head.
        Throwable wrapped = new RuntimeException("outer", new LockAcquisitionException());

        assertTrue(SqliteErrorStrings.isBusy(wrapped));
    }

    @Test
    void testIsBusyWithSqliteBusyAndLockedResultCodes() {
        // 5 = SQLITE_BUSY, 6 = SQLITE_LOCKED. Some drivers report the primary result code here even when
        // the message text has been flattened by a wrapper.
        assertTrue(SqliteErrorStrings.isBusy(new SQLException("write conflict", null, 5)));
        assertTrue(SqliteErrorStrings.isBusy(new SQLException("table locked", null, 6)));
        assertTrue(
                SqliteErrorStrings.isBusy(new RuntimeException("wrapped", new SQLException("busy", null, 5))),
                "the result-code check must also walk the cause chain");
    }

    @Test
    void testIsBusyWithNonRetriableResultCode() {
        // 19 = SQLITE_CONSTRAINT: a genuine failure that must NOT be retried.
        assertFalse(SqliteErrorStrings.isBusy(new SQLException("UNIQUE constraint failed", null, 19)));
    }
}
