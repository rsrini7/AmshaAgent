package com.amsha.persistence.postgres.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amsha.persistence.api.AdvisoryLockPort;
import com.amsha.persistence.postgres.PostgresAdvisoryLockAdapter;
import com.amsha.persistence.postgres.jdbi.LockJdbiDao;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit coverage for {@link PostgresAdvisoryLockAdapter}'s four-method lock protocol.
 *
 * <p>The original class was a two-assertion smoke test (interface assignability + a reflective no-arg
 * constructor call), which left every method body — all 24 lines of {@code tryLock}, {@code unlock},
 * {@code isLocked} and {@code renew} — uncovered. The adapter is a pure delegate onto
 * {@link LockJdbiDao}, so the meaningful assertions are about its contract edges rather than its SQL:
 *
 * <ul>
 *   <li>{@code tryLock} and {@code unlock} translate any failure into a {@link RuntimeException} and must
 *       keep the lock key in the message — a silently-lost lock is worse than a loud failure.
 *   <li>{@code isLocked} and {@code renew} do the opposite: they swallow failures and report
 *       {@code false}. A dead database must read as "not locked" so callers degrade rather than crash.
 * </ul>
 *
 * <p>Each DAO outcome is driven twice — a truthy and a falsy affected-row count — because the
 * {@code > 0} / {@code <= 0} split is where the port's boolean contract actually lives.
 */
class PostgresAdvisoryLockAdapterTest {

    private Jdbi jdbi;
    private LockJdbiDao dao;
    private Handle handle;
    private PostgresAdvisoryLockAdapter adapter;

    @BeforeEach
    void setUp() {
        jdbi = mock(Jdbi.class);
        dao = mock(LockJdbiDao.class);
        handle = mock(Handle.class);
        when(handle.attach(LockJdbiDao.class)).thenReturn(dao);
        adapter = new PostgresAdvisoryLockAdapter(jdbi);
    }

    /** Runs the callback the adapter hands to {@code inTransaction} against a mocked handle. */
    private void givenTransaction() {
        when(jdbi.inTransaction(any())).thenAnswer(invocation -> {
            HandleCallback<Object, RuntimeException> callback = invocation.getArgument(0);
            return callback.withHandle(handle);
        });
    }

    /** Runs the callback the adapter hands to {@code withExtension} against the mocked DAO. */
    private void givenExtension() {
        when(jdbi.withExtension(eq(LockJdbiDao.class), any())).thenAnswer(invocation -> {
            ExtensionCallback callback = invocation.getArgument(1);
            return callback.withExtension(dao);
        });
    }

    /** Runs the consumer the adapter hands to {@code useExtension} against the mocked DAO. */
    private void givenConsumer() {
        doAnswer(invocation -> {
                    ExtensionConsumer consumer = invocation.getArgument(1);
                    consumer.useExtension(dao);
                    return null;
                })
                .when(jdbi)
                .useExtension(eq(LockJdbiDao.class), any());
    }

    // =========================================================================
    // Shape
    // =========================================================================

    @Test
    @DisplayName("the adapter is an AdvisoryLockPort and a bare instance can be constructed")
    void implementsAdvisoryLockPort() {
        assertTrue(AdvisoryLockPort.class.isAssignableFrom(PostgresAdvisoryLockAdapter.class));
    }

    @Test
    @DisplayName("the protected no-arg constructor builds an unconfigured instance")
    void hasDefaultConstructor() {
        // Kept for subclasses and tests; constructing it must not itself touch the database.
        assertDoesNotThrow(() -> {
            var ctor = PostgresAdvisoryLockAdapter.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            ctor.newInstance();
        });
    }

    // =========================================================================
    // tryLock
    // =========================================================================

    @Test
    @DisplayName("tryLock clears the expired row first, then reports success when the insert landed")
    void tryLockSucceedsWhenTheInsertAffectsARow() {
        givenTransaction();
        when(dao.tryInsert(eq("resource"), any(), any())).thenReturn(1);

        assertTrue(adapter.tryLock("resource", 30));

        // Order matters: an expired row for the same key would otherwise make ON CONFLICT DO NOTHING a no-op.
        var inOrder = Mockito.inOrder(dao);
        inOrder.verify(dao).deleteExpired(eq("resource"), any());
        inOrder.verify(dao).tryInsert(eq("resource"), any(), any());
    }

    @Test
    @DisplayName("tryLock reports failure when ON CONFLICT DO NOTHING inserted nothing")
    void tryLockFailsWhenTheInsertAffectsNoRow() {
        givenTransaction();
        when(dao.tryInsert(eq("resource"), any(), any())).thenReturn(0);

        assertFalse(adapter.tryLock("resource", 30));
    }

    @Test
    @DisplayName("tryLock surfaces a database failure as a RuntimeException naming the key")
    void tryLockWrapsAnyFailureInARuntimeException() {
        IllegalStateException cause = new IllegalStateException("db down");
        when(jdbi.inTransaction(any())).thenThrow(cause);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> adapter.tryLock("resource", 30));

        assertTrue(thrown.getMessage().contains("resource"), "the failing key must be in the message");
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    // =========================================================================
    // unlock
    // =========================================================================

    @Test
    @DisplayName("unlock deletes only the calling owner's row")
    void unlockDeletesTheOwnersRow() {
        givenConsumer();

        adapter.unlock("resource");

        verify(dao).deleteByOwner(eq("resource"), any());
    }

    @Test
    @DisplayName("unlock surfaces a database failure as a RuntimeException naming the key")
    void unlockWrapsAnyFailureInARuntimeException() {
        IllegalStateException cause = new IllegalStateException("db down");
        doThrow(cause).when(jdbi).useExtension(eq(LockJdbiDao.class), any());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> adapter.unlock("resource"));

        assertTrue(thrown.getMessage().contains("resource"));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
    }

    // =========================================================================
    // isLocked
    // =========================================================================

    @Test
    @DisplayName("isLocked is true only when an unexpired row exists")
    void isLockedIsTrueWhenAnActiveRowExists() {
        givenExtension();
        when(dao.countActive(eq("resource"), any())).thenReturn(1L);

        assertTrue(adapter.isLocked("resource"));
    }

    @Test
    @DisplayName("isLocked is false when the count is zero")
    void isLockedIsFalseWhenNoActiveRowExists() {
        givenExtension();
        when(dao.countActive(eq("resource"), any())).thenReturn(0L);

        assertFalse(adapter.isLocked("resource"));
    }

    @Test
    @DisplayName("isLocked swallows a database failure and reports unlocked")
    void isLockedSwallowsFailuresAndReportsUnlocked() {
        when(jdbi.withExtension(eq(LockJdbiDao.class), any())).thenThrow(new IllegalStateException("db down"));

        // Deliberately not a throw: callers branch on this to decide whether to attempt a lock.
        assertFalse(adapter.isLocked("resource"));
    }

    // =========================================================================
    // renew
    // =========================================================================

    @Test
    @DisplayName("renew reports success when the owner-scoped update landed")
    void renewSucceedsWhenTheUpdateAffectsARow() {
        givenExtension();
        when(dao.renew(eq("resource"), any(), any())).thenReturn(1);

        assertTrue(adapter.renew("resource", 3600));
    }

    @Test
    @DisplayName("renew reports failure when the row belongs to another owner")
    void renewFailsWhenTheUpdateAffectsNoRow() {
        givenExtension();
        when(dao.renew(eq("resource"), any(), any())).thenReturn(0);

        assertFalse(adapter.renew("resource", 3600));
    }

    @Test
    @DisplayName("renew swallows a database failure and reports failure")
    void renewSwallowsFailuresAndReportsFailure() {
        when(jdbi.withExtension(eq(LockJdbiDao.class), any())).thenThrow(new IllegalStateException("db down"));

        assertFalse(adapter.renew("resource", 3600));
    }
}
