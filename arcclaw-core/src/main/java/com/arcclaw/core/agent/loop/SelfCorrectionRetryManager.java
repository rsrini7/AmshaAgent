package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Manages the schema self-correction retry budget for structured-output
 * failures.
 *
 * When the LLM returns a response that fails schema validation,
 * this manager:
 * <ol>
 *   <li>Checks whether the retry budget is still available.</li>
 *   <li>Increments the error counter.</li>
 *   <li>Produces a correction-hint string that is injected as
 *       {@code transientError} for the next LLM turn.</li>
 * </ol>
 *
 * <h2>Key invariants</h2>
 *
 *
 * <strong>§3.7 (ordering safety):</strong>
 * The retry counter is a plain {@code int}, NOT an {@link java.util.concurrent.atomic.AtomicInteger}.
 * This prevents the CAS-before-threshold ordering violation described in §3.7
 * from ever being introducible. Loop-scoped counters do not need CAS.
 *
 * <strong>transientError ownership:</strong>
 * This manager produces and returns the correction hint string. The
 * {@code transientError} loop variable stays loop-scoped in
 * {@link AgentStateMachineRunner#run} because it is also written by the
 * write-intent injection on iteration 1. The manager must NOT own it.
 *
 * No persistence, no transactions, no CDI state.
 */
@ApplicationScoped
public class SelfCorrectionRetryManager implements CorrectionStrategy {

    /**
     * Maximum number of schema self-correction retries before the loop is
     * treated as exceeded.
     */
    static final int MAX_SCHEMA_CORRECTION_RETRIES = 4;

    @Override
    public boolean withinBudget(int currentRetryCount) {
        return currentRetryCount < MAX_SCHEMA_CORRECTION_RETRIES;
    }

    @Override
    public String buildCorrectionHint(int newCount, String failureReason) {
        if (newCount > MAX_SCHEMA_CORRECTION_RETRIES) {
            return "System: Schema self-correction budget exhausted. Last error: " + failureReason;
        }

        return "System: Your previous response did not conform to the required output schema (attempt "
                + newCount + "/" + MAX_SCHEMA_CORRECTION_RETRIES + "): "
                + failureReason
                + ". Please respond with a fully valid, schema-compliant JSON output. "
                + "Do not include any prose or markdown - only the JSON object.";
    }
}
