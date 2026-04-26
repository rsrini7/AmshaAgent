package com.arcclaw.persistence.sqlite;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Unremovable;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Retry template for SQLite BUSY handling.
 * 
 * Encapsulated inside arcclaw-persistence-sqlite adapter module.
 * NOT exposed as a port interface to core — retry is adapter-internal.
 * 
 * Rationale (Architecture Invariants §5.14.1):
 * Retry-on-BUSY is a SQLite-specific infrastructure concern. Postgres uses MVCC
 * and doesn't need this pattern. Exposing a retry port to core would force every
 * backend adapter to participate in an architecture that's irrelevant to them.
 */
@ApplicationScoped
@Unremovable
public class SqliteRetryTemplate {

    private static final Logger log = Logger.getLogger(SqliteRetryTemplate.class);

    private static final long MIN_DELAY_MS = 10;
    private static final long MAX_DELAY_MS = 2000;
    private static final int DEFAULT_MAX_ATTEMPTS = 10;

    @Inject
    Instance<MeterRegistry> meterRegistry;

    /**
     * Executes a callable action with exponential backoff retry on SQLite BUSY errors.
     * 
     * @param operationName descriptive name for logging
     * @param maxAttempts maximum retries (clamped to 10)
     * @param initialBackoffMs initial backoff interval in milliseconds
     * @param action the callable to execute
     * @param <T> return type
     * @return result of successful action
     * @throws Exception if action fails or BUSY retries are exhausted
     */
    public <T> T execute(String operationName, int maxAttempts, long initialBackoffMs,
                         Callable<T> action) throws Exception {
        
        int limit = maxAttempts > 0 ? Math.min(maxAttempts, DEFAULT_MAX_ATTEMPTS) : DEFAULT_MAX_ATTEMPTS;
        long baseDelay = initialBackoffMs > 0 ? initialBackoffMs : MIN_DELAY_MS;
        
        Exception lastException = null;
        for (int attempt = 1; attempt <= limit; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (!SqliteErrorStrings.isBusy(e)) throw e;
                lastException = e;

                // Bounded Exponential Backoff: base * 2^attempt
                // capped at MAX_DELAY_MS (2s)
                long expDelay = Math.min(MAX_DELAY_MS, baseDelay * (1L << attempt));
                
                // Jitter: ±25% of the calculated delay
                long jitter = (long) (expDelay * 0.25);
                long delay = expDelay + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
                
                // Final safety clamp
                delay = Math.max(MIN_DELAY_MS, delay);

                String compactTrace = ExceptionUtils.getThrowableList(e).stream()
                        .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                        .collect(Collectors.joining("\n  -> "));
                
                log.warnf("SQLite BUSY on [%s], attempt %d/%d — retrying in %dms.\nCause Chain:\n%s",
                          operationName, attempt, limit, delay, compactTrace);
                
                if (attempt < limit) {
                    Thread.sleep(delay);
                }
            }
        }

        // Terminal exhaustion
        log.errorf("CRITICAL: SQLite BUSY exhaustion on [%s] after %d attempts. Giving up.", 
                  operationName, limit);
        
        if (meterRegistry != null && !meterRegistry.isUnsatisfied()) {
            meterRegistry.get().counter("arcclaw.persistence.sqlite.retry.exhausted", 
                "operation", operationName).increment();
        }

        throw new RuntimeException(
            "SQLite BUSY after " + limit + " attempts on [" + operationName + "]",
            lastException);
    }

    public void executeVoid(String operationName, int maxAttempts, long backoffMs,
                            ThrowingRunnable action) throws Exception {
        execute(operationName, maxAttempts, backoffMs, () -> { action.run(); return null; });
    }

    @FunctionalInterface
    public interface ThrowingRunnable { 
        void run() throws Exception; 
    }
}
