package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.structured.StructuredOutputException;

import org.jboss.logging.Logger;

/**
 * Manages the schema self-correction retry budget for structured-output
 * failures.
 *
 * <p>When the LLM returns a response that fails schema validation
 * ({@link StructuredOutputException}), this manager:
 * <ol>
 *   <li>Checks whether the retry budget is still available.</li>
 *   <li>Increments the error counter.</li>
 *   <li>Produces a correction-hint string that is injected as
 *       {@code transientError} for the next LLM turn.</li>
 * </ol>
 *
 * <h2>Key invariants</h2>
 *
 * <p><strong>§3.5 + §8.6 (MUST NOT catch):</strong>
 * {@link BudgetExceededException} and {@link BudgetWarningException} MUST
 * propagate unchanged. This manager never catches them — they flow up to
 * {@link AgentLoop}'s catch block unchanged.
 *
 * <p><strong>§3.7 (ordering safety):</strong>
 * The retry counter is a plain {@code int}, NOT an {@link java.util.concurrent.atomic.AtomicInteger}.
 * This prevents the CAS-before-threshold ordering violation described in §3.7
 * from ever being introducible. Loop-scoped counters do not need CAS.
 *
 * <p><strong>transientError ownership:</strong>
 * This manager produces and returns the correction hint string. The
 * {@code transientError} loop variable stays loop-scoped in
 * {@link AgentStateMachineRunner#run} because it is also written by the
 * write-intent injection on iteration 1. The manager must NOT own it.
 *
 * <p>No persistence, no transactions, no CDI state.
 */
@ApplicationScoped
public class SelfCorrectionRetryManager {

    private static final Logger log = Logger.getLogger(SelfCorrectionRetryManager.class);

    /**
     * Maximum number of schema self-correction retries before the loop is
     * treated as exceeded (matches the hard-coded limit previously in
     * AgentStateMachineRunner).
     */
    static final int MAX_SCHEMA_CORRECTION_RETRIES = 2;

    /**
     * Represents the outcome of a schema-error retry decision.
     *
     * @param correctionHint   the hint to inject as {@code transientError}
     *                         in the next LLM turn; never {@code null}
     * @param newErrorCount    the updated error count (caller assigns to its
     *                         loop variable)
     * @param budgetExhausted  {@code true} when the budget is exhausted and the
     *                         caller should terminate the loop
     */
    public record RetryDecision(String correctionHint,
                                int newErrorCount,
                                boolean budgetExhausted) {}

    /**
     * Processes a {@link StructuredOutputException}, increments the error
     * counter, and produces a {@link RetryDecision} describing what the loop
     * should do next.
     *
     * <p><strong>§3.5 / §8.6:</strong> This method intentionally does NOT
     * catch {@link BudgetExceededException} or {@link BudgetWarningException}.
     * If either is thrown by any delegate called in the future, they propagate
     * unchanged.
     *
     * @param soe              the schema error that triggered the retry
     * @param currentErrorCount the value of the caller's {@code llmSchemaErrorCount}
     *                         loop variable before this call
     * @param sessionId        the active session ID (for logging)
     * @param iteration        the current loop iteration (for logging)
     * @param maxIterations    the configured max iterations (for logging)
     * @return a {@link RetryDecision} — never {@code null}
     */
    public RetryDecision onSchemaError(StructuredOutputException soe,
                                       int currentErrorCount,
                                       String sessionId,
                                       int iteration,
                                       int maxIterations) {

        int newCount = currentErrorCount + 1;

        if (newCount > MAX_SCHEMA_CORRECTION_RETRIES) {
            log.warnf("StateMachine schema self-correction exhausted at iteration %d/%d for session %s — "
                    + "treating as loop exceeded to preserve LOOP_MAX_ITERATIONS audit semantics.",
                    iteration, maxIterations, sessionId);
            return new RetryDecision(
                    buildExhaustedHint(soe),
                    newCount,
                    true);
        }

        log.warnf("StateMachine schema self-correction attempt %d/%d for session %s — injecting correction hint.",
                newCount, MAX_SCHEMA_CORRECTION_RETRIES, sessionId);

        String hint = buildCorrectionHint(soe, newCount);
        return new RetryDecision(hint, newCount, false);
    }

    // -------------------------------------------------------------------------
    // Hint construction
    // -------------------------------------------------------------------------

    private String buildCorrectionHint(StructuredOutputException soe, int attempt) {
        String detail = soe.getMessage() != null ? soe.getMessage() : "schema validation failed";
        return "System: Your previous response did not conform to the required output schema (attempt "
                + attempt + "/" + MAX_SCHEMA_CORRECTION_RETRIES + "): "
                + detail
                + ". Please respond with a fully valid, schema-compliant JSON output. "
                + "Do not include any prose or markdown — only the JSON object.";
    }

    private String buildExhaustedHint(StructuredOutputException soe) {
        String detail = soe.getMessage() != null ? soe.getMessage() : "schema validation failed";
        return "System: Schema self-correction budget exhausted. Last error: " + detail;
    }
}
