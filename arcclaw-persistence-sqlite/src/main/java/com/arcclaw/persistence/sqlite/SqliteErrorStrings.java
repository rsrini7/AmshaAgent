package com.arcclaw.persistence.sqlite;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Utility constants and methods for detecting SQLite error conditions in retry logic.
 *
 * Encapsulated inside arcclaw-persistence-sqlite adapter module.
 * Used by {@link SqliteRetryTemplate} to identify BUSY/locked conditions.
 */
public final class SqliteErrorStrings {
    private SqliteErrorStrings() {}

    public static final String DATABASE_LOCKED = "database is locked";
    public static final String SQLITE_BUSY = "sqlite_busy";
    public static final String BUSY = "busy";
    public static final String NO_TRANSACTION_ACTIVE = "no transaction is active";
    public static final String SQLITE_EXCEPTION = "SQLiteException";
    public static final String LOCK_ACQUISITION_EXCEPTION = "LockAcquisitionException";
    public static final String OPTIMISTIC_LOCK_EXCEPTION = "OptimisticLockException";
    public static final String SNAPSHOT = "snapshot";

    /**
     * Returns true if the given exception chain contains an SQLite "busy" or "locked" error.
     *
     * Traverses the full exception cause chain because SQL exceptions may be wrapped
     * several layers deep (e.g., PersistenceException -> SQLException -> SQLiteException).
     *
     * Also detects transaction conflict patterns like lock acquisition failures,
     * optimistic lock exceptions, and snapshot isolation failures.
     */
    public static boolean isBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String canonicalName = t.getClass().getName();

            // 1. Explicit Lock/Optimistic exceptions from JPA
            if (canonicalName.contains(LOCK_ACQUISITION_EXCEPTION)
                    || canonicalName.contains(OPTIMISTIC_LOCK_EXCEPTION)) {
                return true;
            }

            // 2. Specific SQLite Result Codes if accessible via SQLException
            if (t instanceof SQLException se) {
                int errorCode = se.getErrorCode();
                // 5 = SQLITE_BUSY, 6 = SQLITE_LOCKED
                // Some drivers return the primary result code in getErrorCode()
                if (errorCode == 5 || errorCode == 6) {
                    return true;
                }
            }

            // 3. String-based fallback (essential because some drivers or wrappers flatten info)
            // Narrowed to avoid matching non-retriable SQLITE_CONSTRAINT or SQLITE_CORRUPT
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);

                // Retriable patterns
                boolean isRetriable = lower.contains(BUSY)
                        || lower.contains(DATABASE_LOCKED)
                        || lower.contains(SQLITE_BUSY)
                        || lower.contains(SNAPSHOT)
                        || lower.contains(NO_TRANSACTION_ACTIVE);

                // Explicitly exclude known non-retriable errors even if "sqlite" or "exception" string is present
                boolean isNonRetriable = lower.contains("constraint")
                        || lower.contains("corrupt")
                        || lower.contains("readonly")
                        || lower.contains("syntax")
                        || lower.contains("no such table");

                if (isRetriable && !isNonRetriable) {
                    return true;
                }
            }
        }
        return false;
    }
}
