package com.amsha.classifier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link DecisionTransactionGuard} construction and status-reading surface (§5.9).
 *
 * <p>{@code InMemoryDecisionEngineTest} already pins the status truth table, the {@link IntSupplier}
 * constructor, and the {@code resolveStatus} decision matrix. This class covers the parts those tests leave
 * dark: the {@link TransactionManager}-backed constructor and its {@code of} factory, the
 * {@code ofStatusSupplier} factory, {@code currentStatus()}, and — most importantly — the fail-closed
 * behaviour when reading the JTA status itself throws. A guard that silently reported "no transaction" on a
 * failed status read would let decision evaluation run inside an active write transaction, which is the one
 * thing this class exists to prevent.
 *
 * <p>One region is left uncovered on purpose: {@code defaultStatusSupplier}'s Arc-container branch. Reaching
 * it requires {@code Arc.container()} to be non-null, which means either a running CDI container
 * ({@code @QuarkusTest} plus {@code quarkus-junit5}) or a static mock ({@code mockStatic(Arc.class)}). This
 * module declares neither, so closing it would first need a test-scoped Mockito dependency added to
 * {@code classifier-api/pom.xml} — a change to the module's build surface, not to its tests. The
 * <em>decision</em> that branch makes ({@code resolveStatus(true, false, …)} fails closed) is already covered
 * by {@code InMemoryDecisionEngineTest}; only the container-discovery wiring above it is dark here.
 *
 * <p>{@code DecisionTransactionGuard.noTransaction()} is separately unreachable. It is only ever passed as a
 * method reference into {@code resolveStatus}, from the two branches where the container is absent or the
 * manager is unavailable — and {@code resolveStatus} returns before consulting its supplier in both. The body
 * never executes, so its instructions are dead code rather than a coverage gap.
 */
class DecisionTransactionGuardTest {

    // ---------------------------------------------------------------- factories and constructors

    @Test
    void ofStatusSupplierExposesTheRawStatus() {
        DecisionTransactionGuard guard = DecisionTransactionGuard.ofStatusSupplier(() -> Status.STATUS_PREPARING);

        assertEquals(Status.STATUS_PREPARING, guard.currentStatus());
        assertTrue(guard.isInActiveTransaction());
    }

    @Test
    void transactionManagerBackedGuardReadsThroughToTheManager() {
        DecisionTransactionGuard free = DecisionTransactionGuard.of(managerReporting(Status.STATUS_NO_TRANSACTION));

        assertEquals(Status.STATUS_NO_TRANSACTION, free.currentStatus());
        assertFalse(free.isInActiveTransaction());
        free.ensureTransactionFree(); // must not throw

        DecisionTransactionGuard active = DecisionTransactionGuard.of(managerReporting(Status.STATUS_ACTIVE));

        assertEquals(Status.STATUS_ACTIVE, active.currentStatus());
        assertTrue(active.isInActiveTransaction());
    }

    @Test
    void transactionManagerBackedGuardFailsClosedWhenTheStatusReadThrows() {
        // A manager whose getStatus() raises SystemException must be treated as "active", never as
        // "transaction-free": the guard cannot verify the boundary, so it refuses to certify it.
        DecisionTransactionGuard guard = DecisionTransactionGuard.of(managerThatThrowsOnStatus());

        assertEquals(Status.STATUS_ACTIVE, guard.currentStatus());
        assertTrue(guard.isInActiveTransaction());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, guard::ensureTransactionFree);
        assertTrue(thrown.getMessage().contains("Active JTA transaction detected"), thrown.getMessage());
    }

    @Test
    void constructorAndFactoriesRejectNullCollaborators() {
        assertEquals(
                "transactionManager",
                assertThrows(NullPointerException.class, () -> new DecisionTransactionGuard((TransactionManager) null))
                        .getMessage());
        assertEquals(
                "statusSupplier",
                assertThrows(NullPointerException.class, () -> new DecisionTransactionGuard((IntSupplier) null))
                        .getMessage());
        assertThrows(NullPointerException.class, () -> DecisionTransactionGuard.of(null));
        assertThrows(NullPointerException.class, () -> DecisionTransactionGuard.ofStatusSupplier(null));
    }

    // ---------------------------------------------------------------- delegation and enforcement

    @Test
    void currentStatusDelegatesOnEveryReadRatherThanCachingTheFirstOne() {
        // The status supplier is read per call, so a guard that observed a transaction-free context is not
        // allowed to keep certifying later calls made inside a transaction.
        int[] status = {Status.STATUS_NO_TRANSACTION};
        DecisionTransactionGuard guard = DecisionTransactionGuard.ofStatusSupplier(() -> status[0]);

        assertFalse(guard.isInActiveTransaction());
        guard.ensureTransactionFree();

        status[0] = Status.STATUS_ACTIVE;

        assertEquals(Status.STATUS_ACTIVE, guard.currentStatus());
        assertTrue(guard.isInActiveTransaction());
        assertThrows(IllegalStateException.class, guard::ensureTransactionFree);
    }

    @Test
    void ensureTransactionFreeNamesTheInvariantItEnforcesWithoutLeakingPayload() {
        DecisionTransactionGuard guard = DecisionTransactionGuard.ofStatusSupplier(() -> Status.STATUS_MARKED_ROLLBACK);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, guard::ensureTransactionFree);

        assertTrue(thrown.getMessage().contains("decision evaluation is forbidden"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.9"), thrown.getMessage());
    }

    @Test
    void isTransactionActiveTreatsEveryInFlightAndUnknownStatusAsActive() {
        int[] active = {
            Status.STATUS_ACTIVE,
            Status.STATUS_PREPARING,
            Status.STATUS_PREPARED,
            Status.STATUS_COMMITTING,
            Status.STATUS_ROLLING_BACK,
            Status.STATUS_MARKED_ROLLBACK,
            Status.STATUS_UNKNOWN
        };
        for (int status : active) {
            assertTrue(DecisionTransactionGuard.isTransactionActive(status), "expected active: " + status);
        }

        int[] inactive = {Status.STATUS_NO_TRANSACTION, Status.STATUS_COMMITTED, Status.STATUS_ROLLEDBACK};
        for (int status : inactive) {
            assertFalse(DecisionTransactionGuard.isTransactionActive(status), "expected inactive: " + status);
        }

        // An unrecognised code is not a licence to proceed.
        assertFalse(DecisionTransactionGuard.isTransactionActive(999));
    }

    // ---------------------------------------------------------------- stub collaborator

    private static TransactionManager managerReporting(int status) {
        return new StubTransactionManager(status, null);
    }

    private static TransactionManager managerThatThrowsOnStatus() {
        return new StubTransactionManager(0, new SystemException("status unavailable"));
    }

    /**
     * Hand-rolled stand-in for a JTA {@link TransactionManager}. No mocking framework is available to this
     * module, and only {@code getStatus()} is ever reached by the guard, so every other operation fails loudly
     * if the guard ever starts calling it.
     */
    private static final class StubTransactionManager implements TransactionManager {

        private final int status;
        private final SystemException failure;

        StubTransactionManager(int status, SystemException failure) {
            this.status = status;
            this.failure = failure;
        }

        @Override
        public int getStatus() throws SystemException {
            if (failure != null) {
                throw failure;
            }
            return status;
        }

        @Override
        public void begin() {
            throw new UnsupportedOperationException("the guard must not begin a transaction");
        }

        @Override
        public void commit() {
            throw new UnsupportedOperationException("the guard must not commit a transaction");
        }

        @Override
        public void rollback() {
            throw new UnsupportedOperationException("the guard must not roll back a transaction");
        }

        @Override
        public void setRollbackOnly() {
            throw new UnsupportedOperationException("the guard must not mark a transaction for rollback");
        }

        @Override
        public void setTransactionTimeout(int seconds) {
            throw new UnsupportedOperationException("the guard must not set a transaction timeout");
        }

        @Override
        public Transaction getTransaction() {
            throw new UnsupportedOperationException("the guard must not inspect the transaction object");
        }

        @Override
        public void resume(Transaction transaction) {
            throw new UnsupportedOperationException("the guard must not resume a transaction");
        }

        @Override
        public Transaction suspend() {
            throw new UnsupportedOperationException("the guard must not suspend a transaction");
        }
    }
}
