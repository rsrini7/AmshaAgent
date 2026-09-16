package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-database coverage for {@link SqliteAdvisoryLockAdapter}.
 *
 * <p>The existing {@code adapter.SqliteAdvisoryLockAdapterTest} mocks {@link JdbiRetryWrapper},
 * so the lambdas handed to it never run and none of the adapter's SQL is exercised — 20 uncovered
 * lines beside a green test class. Here the wrapper is the real one and the database is a real
 * SQLite file, so every branch of the lock protocol is observable through its own effects.
 *
 * <p>Two adapter instances stand in for two contenders: {@code ownerId} is a per-instance UUID,
 * which is exactly how the protocol distinguishes owners.
 */
class SqliteAdvisoryLockAdapterDeepTest {

    private static final String DDL = """
            CREATE TABLE advisory_lock (
                lock_key   TEXT NOT NULL PRIMARY KEY,
                owner_id   TEXT,
                expires_at TEXT
            )
            """;

    @TempDir
    Path tempDir;

    private Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = Jdbi.create("jdbc:sqlite:" + tempDir.resolve("locks.db").toAbsolutePath())
                .installPlugin(new SqlObjectPlugin());
        jdbi.useHandle(handle -> handle.execute(DDL));
    }

    private SqliteAdvisoryLockAdapter contender() {
        return new SqliteAdvisoryLockAdapter(jdbi, new JdbiRetryWrapper());
    }

    @Test
    void tryLockSucceedsThenBlocksASecondOwner() {
        SqliteAdvisoryLockAdapter first = contender();
        SqliteAdvisoryLockAdapter second = contender();

        assertTrue(first.tryLock("resource", 30));
        assertFalse(second.tryLock("resource", 30), "a live lock must not be handed to a second owner");
        assertTrue(first.isLocked("resource"));
    }

    @Test
    void tryLockOnDistinctKeysDoesNotContend() {
        SqliteAdvisoryLockAdapter first = contender();
        SqliteAdvisoryLockAdapter second = contender();

        assertTrue(first.tryLock("key-a", 30));
        assertTrue(second.tryLock("key-b", 30));
    }

    @Test
    void unlockReleasesTheLockForItsOwnOwner() {
        SqliteAdvisoryLockAdapter first = contender();
        SqliteAdvisoryLockAdapter second = contender();
        first.tryLock("resource", 30);

        first.unlock("resource");

        assertFalse(first.isLocked("resource"));
        assertTrue(second.tryLock("resource", 30), "the released lock must become claimable");
    }

    @Test
    void unlockByANonOwnerLeavesTheLockInPlace() {
        SqliteAdvisoryLockAdapter first = contender();
        SqliteAdvisoryLockAdapter second = contender();
        first.tryLock("resource", 30);

        second.unlock("resource");

        assertTrue(first.isLocked("resource"), "deleteByOwner must not remove another owner's row");
        assertFalse(second.tryLock("resource", 30));
    }

    @Test
    void renewExtendsTheLockForItsOwner() {
        SqliteAdvisoryLockAdapter first = contender();
        first.tryLock("resource", 30);

        assertTrue(first.renew("resource", 3600));
        assertTrue(first.isLocked("resource"));
    }

    @Test
    void renewByANonOwnerReportsFailure() {
        SqliteAdvisoryLockAdapter first = contender();
        SqliteAdvisoryLockAdapter second = contender();
        first.tryLock("resource", 30);

        assertFalse(second.renew("resource", 3600), "renew is scoped to the owner_id");
    }

    @Test
    void renewOnAnUnknownKeyReportsFailure() {
        assertFalse(contender().renew("never-locked", 30));
    }

    @Test
    void anExpiredLockIsReclaimableByAnotherOwner() {
        SqliteAdvisoryLockAdapter first = contender();
        SqliteAdvisoryLockAdapter second = contender();

        // A negative timeout stores an already-past expiry, so deleteExpired should clear it.
        assertTrue(first.tryLock("resource", -1));

        assertTrue(second.tryLock("resource", 30), "an expired lock must be reclaimable");
        assertTrue(second.isLocked("resource"));
    }

    @Test
    void isLockedIsFalseForAnUnknownKeyAndForAnExpiredLock() {
        SqliteAdvisoryLockAdapter first = contender();

        assertFalse(first.isLocked("never-locked"));

        first.tryLock("stale", -1);
        assertFalse(first.isLocked("stale"), "countActive only counts rows whose expiry is in the future");
    }

    @Test
    void theProtectedNoArgConstructorBuildsAnUnconfiguredInstance() {
        // Kept for subclasses and tests that need a bare instance; constructing it must not itself
        // touch the database.
        SqliteAdvisoryLockAdapter bare = new SqliteAdvisoryLockAdapter();

        assertNotNull(bare);
    }
}
