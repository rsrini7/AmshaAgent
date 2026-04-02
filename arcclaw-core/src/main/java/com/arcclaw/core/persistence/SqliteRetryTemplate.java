package com.arcclaw.core.persistence;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

@ApplicationScoped
public class SqliteRetryTemplate {

    private static final Logger log = Logger.getLogger(SqliteRetryTemplate.class);

    public <T> T execute(String operationName, int maxAttempts, long backoffMs,
                         Callable<T> action) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (!SqliteErrorStrings.isBusy(e)) throw e; // not BUSY — rethrow immediately
                lastException = e;

                // Exponential backoff: base * 2^attempt + random jitter (0–50ms)
                // Prevents retry stampede when multiple threads fail at same time
                long delay = (backoffMs * (1L << attempt))
                           + ThreadLocalRandom.current().nextLong(0, 50);

                log.warnf("SQLite BUSY on [%s], attempt %d/%d — retrying in %dms",
                          operationName, attempt, maxAttempts, delay);

                if (attempt < maxAttempts) {
                    Thread.sleep(delay);
                }
            }
        }
        throw new RuntimeException(
            "SQLite BUSY after " + maxAttempts + " attempts on [" + operationName + "]",
            lastException);
    }

    public void executeVoid(String operationName, int maxAttempts, long backoffMs,
                            ThrowingRunnable action) throws Exception {
        execute(operationName, maxAttempts, backoffMs, () -> { action.run(); return null; });
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}