package com.arcclaw.persistence.sqlite;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.util.BackoffRetryExecutor;
import io.quarkus.arc.properties.IfBuildProperty;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.jdbi.v3.core.transaction.TransactionException;

/**
 * Lightweight retry wrapper for SQLITE_BUSY/SQLITE_LOCKED errors via JDBI.
 * Reuses {@link SqliteErrorStrings#isBusy} for detection and {@link BackoffRetryExecutor}
 * for exponential backoff.
 */
@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class JdbiRetryWrapper {

    private static final Logger log = Logger.getLogger(JdbiRetryWrapper.class);

    public <T> T withRetry(String opName, int maxAttempts, long initialBackoffMs, Callable<T> action) {
        var strategy = new BackoffRetryExecutor.Strategy(
            Math.max(1, maxAttempts),
            Math.max(10, initialBackoffMs),
            2000,
            0.25
        );
        try {
            return BackoffRetryExecutor.execute(opName, strategy, RETRY_PREDICATE, action);
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException("Retry exhausted: " + opName, e);
        }
    }

    public void withRetryVoid(String opName, int maxAttempts, long initialBackoffMs, ThrowingRunnable action) {
        withRetry(opName, maxAttempts, initialBackoffMs, () -> {
            action.run();
            return null;
        });
    }

    private static final Predicate<Exception> RETRY_PREDICATE = e -> {
        Throwable cause = e;
        if (e instanceof UnableToExecuteStatementException || e instanceof TransactionException) {
            cause = e.getCause() != null ? e.getCause() : e;
        }
        boolean retriable = SqliteErrorStrings.isBusy(cause);
        if (retriable) {
            log.debugf("Retriable SQLite error: %s", e.getMessage());
        }
        return retriable;
    };

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
