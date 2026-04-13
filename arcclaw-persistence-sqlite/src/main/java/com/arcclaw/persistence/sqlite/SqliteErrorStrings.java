package com.arcclaw.persistence.sqlite;

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
    public static final String SQLITE_BUSY     = "sqlite_busy";
    public static final String BUSY            = "busy";
    public static final String SQLITE_EXCEPTION = "SQLiteException";
    public static final String LOCK_ACQUISITION_EXCEPTION = "LockAcquisitionException";
    public static final String OPTIMISTIC_LOCK_EXCEPTION = "OptimisticLockException";
    public static final String SNAPSHOT        = "snapshot";

    /**
     * Returns true if the given exception chain contains an SQLite "busy" or "locked" error.
     * 
     * Traverses the full exception cause chain because Hibernate wraps SQLiteException
     * several layers deep (e.g., PersistenceException -> SQLException -> SQLiteException).
     * 
     * Also detects transaction conflict patterns like lock acquisition failures,
     * optimistic lock exceptions, and snapshot isolation failures.
     */
    public static boolean isBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String canonicalName = t.getClass().getName();
            if (canonicalName.contains(LOCK_ACQUISITION_EXCEPTION) ||
                canonicalName.contains(OPTIMISTIC_LOCK_EXCEPTION) ||
                canonicalName.contains(SQLITE_EXCEPTION)) {
                return true;
            }

            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains(BUSY) || 
                    lower.contains(DATABASE_LOCKED) || 
                    lower.contains(SQLITE_BUSY) ||
                    lower.contains(SNAPSHOT)) {
                    return true;
                }
            }
        }
        return false;
    }
}
