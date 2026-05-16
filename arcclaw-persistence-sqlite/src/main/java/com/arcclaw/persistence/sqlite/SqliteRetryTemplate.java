package com.arcclaw.persistence.sqlite;

import java.util.concurrent.Callable;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.core.util.AgentGuards;
import com.arcclaw.core.util.BackoffRetryExecutor;
import com.arcclaw.core.util.LogUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Unremovable;
import org.jboss.logging.Logger;

/**
 * Retry template for SQLite BUSY handling.
 * 
 * Encapsulated inside arcclaw-persistence-sqlite adapter module.
 * NOT exposed as a port interface to core - retry is adapter-internal.
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
        
        // 0. Shutdown Guard: skip ONLY if the CDI container is actively stopping.
        if (AgentGuards.isShuttingDown()) {
            log.debugf("CDI container shutting down - aborting persistence operation [%s]", operationName);
            return null;
        }

        BackoffRetryExecutor.Strategy strategy = new BackoffRetryExecutor.Strategy(
            maxAttempts > 0 ? Math.min(maxAttempts, DEFAULT_MAX_ATTEMPTS) : DEFAULT_MAX_ATTEMPTS,
            initialBackoffMs > 0 ? initialBackoffMs : MIN_DELAY_MS,
            MAX_DELAY_MS,
            0.25 // Jitter factor
        );

        try {
            return BackoffRetryExecutor.execute(
                operationName,
                strategy,
                e -> {
                    boolean retriable = SqliteErrorStrings.isBusy(e);
                    if (retriable) {
                        if (meterRegistry != null && !meterRegistry.isUnsatisfied()) {
                            meterRegistry.get().counter("arcclaw.persistence.sqlite.retry.attempt", 
                                "operation", operationName).increment();
                        }
                    }
                    return retriable;
                },
                () -> {
                    try {
                        return action.call();
                    } catch (Exception e) {
                        // Log cause chain for debugging SR-12 refinements
                        if (log.isTraceEnabled()) {
                            log.tracef("Persistence attempt failed for [%s]: %s",
                                      operationName, LogUtil.trimmedStack(e));
                        }
                        throw e;
                    }
                }
            );
        } catch (Exception e) {
            if (SqliteErrorStrings.isBusy(e)) {
                if (meterRegistry != null && !meterRegistry.isUnsatisfied()) {
                    meterRegistry.get().counter("arcclaw.persistence.sqlite.retry.exhausted", 
                        "operation", operationName).increment();
                }
                throw new RuntimeException(String.format("SQLite BUSY after %d attempts on [%s]", 
                    strategy.maxAttempts(), operationName), e);
            }
            throw e;
        }
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
