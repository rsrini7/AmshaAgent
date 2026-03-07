package com.arcclaw.core.persistence;

/**
 * Centered constants for SQLite error detection and retry logic.
 */
public final class SqliteErrorStrings {
    private SqliteErrorStrings() {}

    public static final String DATABASE_LOCKED = "database is locked";
    public static final String SQLITE_BUSY     = "sqlite_busy";
    public static final String BUSY            = "busy";
    public static final String SQLITE_EXCEPTION = "SQLiteException";
    public static final String SNAPSHOT        = "snapshot";
}
