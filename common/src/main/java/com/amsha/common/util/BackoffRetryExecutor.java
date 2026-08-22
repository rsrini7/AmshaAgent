package com.amsha.common.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import org.jboss.logging.Logger;

/**
 * Shared utility for exponential backoff with jitter.
 * Consolidates SR-1 logic across the codebase.
 */
public class BackoffRetryExecutor {

    private static final Logger log = Logger.getLogger(BackoffRetryExecutor.class);

    /**
     * Configuration for the retry strategy.
     */
    public record Strategy(int maxAttempts, long initialDelayMs, long maxDelayMs, double jitterFactor) {
        public static final Strategy DEFAULT = new Strategy(10, 10, 2000, 0.25);
        public static final Strategy UI_RECONNECT = new Strategy(10, 1000, 30000, 0.2);
    }

    /**
     * Executes an action with exponential backoff.
     *
     * @param operationName label for logging
     * @param strategy retry parameters
     * @param retryPredicate returns true if the exception is retriable
     * @param action the operation to perform
     * @return result of successful execution
     * @throws Exception the last encountered exception if all retries fail
     */
    public static <T> T execute(
            String operationName, Strategy strategy, Predicate<Exception> retryPredicate, Callable<T> action)
            throws Exception {

        Exception lastException = null;
        int limit = Math.max(1, strategy.maxAttempts());

        for (int attempt = 1; attempt <= limit; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (!retryPredicate.test(e)) {
                    throw e;
                }
                lastException = e;

                if (attempt == limit) {
                    break;
                }

                // Exponential Backoff: base * 2^attempt
                // Safety: attempt is capped by Strategy.maxAttempts (clamped to 10), so no overflow risk.
                long expDelay = Math.min(strategy.maxDelayMs(), strategy.initialDelayMs() * (1L << attempt));

                // Jitter
                long jitter = (long) (expDelay * strategy.jitterFactor());
                long delay = expDelay + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
                delay = Math.max(strategy.initialDelayMs(), delay);

                log.warnf(
                        "[%s] Attempt %d/%d failed (retriable). Retrying in %dms. Cause: %s",
                        operationName, attempt, limit, delay, LogUtil.rootMessage(e));
                Thread.sleep(delay);
            }
        }

        log.errorf("[%s] Critical exhaustion after %d attempts. Giving up.", operationName, limit);
        throw lastException;
    }

    /** Void version of execute. */
    public static void executeVoid(
            String operationName, Strategy strategy, Predicate<Exception> retryPredicate, Runnable action)
            throws Exception {
        execute(operationName, strategy, retryPredicate, () -> {
            action.run();
            return null;
        });
    }
}
