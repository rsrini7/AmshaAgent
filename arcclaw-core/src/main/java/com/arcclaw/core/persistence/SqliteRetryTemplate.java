package com.arcclaw.core.persistence;

import java.util.concurrent.Callable;
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
                if (!SqliteErrorStrings.isBusy(e)) throw e;   // not a BUSY error — rethrow immediately
                lastException = e;
                log.warnf("SQLite BUSY on [%s], attempt %d/%d — retrying in %dms",
                          operationName, attempt, maxAttempts, backoffMs);
                if (attempt < maxAttempts) {
                    long delay = backoffMs * (1L << attempt); // Exponential backoff: base * 2^attempt
                    Thread.sleep(delay);
                }
            }
        }
        throw new RuntimeException("SQLite BUSY after " + maxAttempts + " attempts on [" + operationName + "]",
                                   lastException);
    }

    public void executeVoid(String operationName, int maxAttempts, long backoffMs,
                            ThrowingRunnable action) throws Exception {
        execute(operationName, maxAttempts, backoffMs, () -> { action.run(); return null; });
    }

    @FunctionalInterface
    public interface ThrowingRunnable { void run() throws Exception; }
}
