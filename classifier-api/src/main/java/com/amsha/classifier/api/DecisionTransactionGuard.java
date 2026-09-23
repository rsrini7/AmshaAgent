package com.amsha.classifier.api;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;

import java.util.Objects;
import java.util.function.IntSupplier;

import io.quarkus.arc.Arc;

/**
 * Defensive transaction boundary guard for decision evaluations (§5.9).
 * Decision engine evaluation and provider calls must never execute inside an active JTA write transaction.
 */
public final class DecisionTransactionGuard {

    private final IntSupplier statusSupplier;

    public DecisionTransactionGuard() {
        this(DecisionTransactionGuard::defaultStatusSupplier);
    }

    public DecisionTransactionGuard(TransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.statusSupplier = () -> {
            try {
                return transactionManager.getStatus();
            } catch (Exception _) {
                // If checking transaction status throws, fail closed as active to prevent unverified execution
                return Status.STATUS_ACTIVE;
            }
        };
    }

    public DecisionTransactionGuard(IntSupplier statusSupplier) {
        this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
    }

    public static DecisionTransactionGuard system() {
        return new DecisionTransactionGuard();
    }

    public static DecisionTransactionGuard of(TransactionManager transactionManager) {
        return new DecisionTransactionGuard(transactionManager);
    }

    public static DecisionTransactionGuard ofStatusSupplier(IntSupplier statusSupplier) {
        return new DecisionTransactionGuard(statusSupplier);
    }

    /**
     * Checks whether the current context is inside an active JTA transaction.
     *
     * @return true if an active transaction is detected
     */
    public boolean isInActiveTransaction() {
        int status = statusSupplier.getAsInt();
        return isTransactionActive(status);
    }

    /**
     * Determines if a JTA status code represents an active/in-progress transaction.
     *
     * @param status JTA status integer (from {@link jakarta.transaction.Status})
     * @return true if active, in progress, or unknown (unsafe to assume transaction-free)
     */
    public static boolean isTransactionActive(int status) {
        return status == Status.STATUS_ACTIVE
                || status == Status.STATUS_PREPARING
                || status == Status.STATUS_PREPARED
                || status == Status.STATUS_COMMITTING
                || status == Status.STATUS_ROLLING_BACK
                || status == Status.STATUS_MARKED_ROLLBACK
                || status == Status.STATUS_UNKNOWN;
    }

    /**
     * Enforces that the current context is transaction-free.
     *
     * @throws IllegalStateException if an active transaction is detected
     */
    public void ensureTransactionFree() {
        if (isInActiveTransaction()) {
            throw new IllegalStateException(
                    "Active JTA transaction detected: decision evaluation is forbidden inside an active write transaction (§5.9)");
        }
    }

    /**
     * Returns the current JTA status integer.
     *
     * @return status integer
     */
    public int currentStatus() {
        return statusSupplier.getAsInt();
    }

    private static int defaultStatusSupplier() {
        var container = Arc.container();
        if (container != null && container.isRunning()) {
            try {
                var handle = container.instance(TransactionManager.class);
                if (handle.isAvailable()) {
                    return handle.get().getStatus();
                }
            } catch (Exception _) {
                return Status.STATUS_ACTIVE;
            }
        }
        return Status.STATUS_NO_TRANSACTION;
    }
}
