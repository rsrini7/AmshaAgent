package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.observability.AgentObserver;
import com.amsha.core.observability.AgentOtelContext;
import com.amsha.core.util.AgentErrorMessageUtil;
import com.amsha.core.util.ClassUtils;

import org.jboss.logging.Logger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

/**
 * Manages OpenTelemetry span lifecycle and {@link AgentObserver} callbacks
 * for the agent reasoning loop.
 *
 * <p>Extracted from {@code AgentLoop.runWithRole} as part of
 * God-class decomposition. Owns two of the seven original responsibilities:
 * <ul>
 *   <li><strong>OTel spans</strong> - begin/end the session-level root span
 *       via {@link AgentOtelContext}.</li>
 *   <li><strong>Observer callbacks</strong> - broadcast {@code onLoopComplete}
 *       and {@code onStepComplete} to all registered {@link AgentObserver}
 *       instances.</li>
 * </ul>
 *
 * <h3>🚨 Transaction-free zone (§5.9)</h3>
 * <p>No method in this class may carry {@code @Transactional} or
 * {@code @Transactional(REQUIRED)}. This class is reachable from
 * {@code AgentLoop.run()} which holds no connection; acquiring one here
 * silently reintroduces the P84 SQLITE_BUSY cascade under concurrent
 * approval + cron load.
 *
 * <h3>⚠️ OTel context capture rule (§7.13)</h3>
 * <p>When the caller submits work to a {@code ManagedExecutor}, the current
 * OTel {@link Context} must be captured on the <em>calling thread</em> before
 * the {@code submit()} call via {@link #captureContext()}.
 * {@code ContextAwareExecutorService} propagates workspace context, but it does
 * <strong>not</strong> propagate OTel
 * {@code ThreadLocal}s.
 *
 * <pre>{@code
 * // CORRECT - context captured on calling thread
 * Context otelCtx = observabilityManager.captureContext();
 * executor.submit(() -> {
 *     try (Scope s = otelCtx.makeCurrent()) {
 *         // span work here
 *     }
 * });
 *
 * // WRONG - captured inside lambda, OTel ThreadLocal already gone
 * executor.submit(() -> {
 *     Context.current(); // too late
 * });
 * }</pre>
 *
 * <p>After each class is created, verify no {@code @Transactional} leaked in:
 * <pre>grep -n "@Transactional" ObservabilityManager.java</pre>
 * That must return nothing.
 */
@ApplicationScoped
public class ObservabilityManager {

    private static final Logger log = Logger.getLogger(ObservabilityManager.class);

    @Inject
    AgentOtelContext otelContext;

    @Inject
    Instance<AgentObserver> observers;

    // ── Span lifecycle ────────────────────────────────────────────────────────

    /**
     * Starts the session-level root OTel span and returns it.
     *
     * <p>Must be called on the thread that will later call
     * {@link #captureContext()} - so the returned span is active before any
     * executor submission that needs to propagate OTel context (§7.13).
     *
     * @param sessionId workspace session identifier
     * @return the active root {@link Span}; {@code null} if {@code sessionId}
     *         is {@code null} (delegated to {@link AgentOtelContext})
     */
    public Span beginSession(String sessionId) {
        return otelContext.startSessionSpan(sessionId);
    }

    public Span beginSession(String sessionId, String userId, String agentRole) {
        return otelContext.startSessionSpan(sessionId, userId, agentRole);
    }

    /**
     * Ends the session-level root OTel span and releases per-session token
     * accumulators.
     *
     * <p>Safe to call from a {@code finally} block - logs a warning on
     * failure but never re-throws, to preserve the original loop result.
     *
     * @param sessionId workspace session identifier
     */
    public void endSession(String sessionId) {
        try {
            otelContext.endSessionSpan(sessionId);
        } catch (Exception e) {
            log.warnf(
                    "ObservabilityManager.endSession: failed to end OTel span for session %s: %s",
                    sessionId, AgentErrorMessageUtil.safeMessage(e));
        }
    }

    // ── OTel context capture (§7.13) ──────────────────────────────────────────

    /**
     * Captures the current OTel {@link Context} on the <strong>calling
     * thread</strong>.
     *
     * <p>Store the result in a local variable, then open it inside the
     * executor lambda with {@code otelCtx.makeCurrent()}:
     *
     * <pre>{@code
     * Context otelCtx = observabilityManager.captureContext(); // calling thread
     * executor.submit(() -> {
     *     try (Scope s = otelCtx.makeCurrent()) {
     *         // child span work here
     *     }
     * });
     * }</pre>
     *
     * @return current OTel {@link Context}; never {@code null}
     */
    public Context captureContext() {
        return Context.current();
    }

    // ── Observer callbacks ────────────────────────────────────────────────────

    /**
     * Broadcasts {@link AgentObserver#onLoopComplete} to every registered
     * observer.
     *
     * <p>Intended for the {@code finally} block of
     * {@code AgentLoop.runWithRole}. Swallows per-observer exceptions so that
     * a misbehaving observer cannot suppress the loop result.
     *
     * @param sessionId  workspace session identifier
     * @param userId     the user who owns this session (use the value captured
     *                   before entering the {@code try} block - MDC may be
     *                   stale in {@code finally})
     * @param agentRole  canonical agent role
     * @param goalHash   HMAC-SHA256 of the original goal for audit chaining;
     *                   {@code null} when {@code result} is also {@code null}
     * @param result     final {@link AgentResult}; {@code null} if the loop
     *                   terminated before producing a result
     */
    public void notifyLoopComplete(
            String sessionId, String userId, String agentRole, String goalHash, AgentResult result) {
        observers.forEach(o -> {
            try {
                o.onLoopComplete(sessionId, userId, agentRole, goalHash, result);
            } catch (Exception e) {
                log.warnf(
                        "ObservabilityManager: observer %s threw on onLoopComplete for session %s: %s",
                        ClassUtils.getCleanName(o), sessionId, AgentErrorMessageUtil.safeMessage(e));
            }
        });
    }

    /**
     * Broadcasts {@link AgentObserver#onStepComplete} to every registered
     * observer.
     *
     * <p>Swallows per-observer exceptions to keep the loop running.
     *
     * @param sessionId workspace session identifier
     * @param agentRole canonical agent role
     * @param step      the completed {@link ReasoningStep} with observation
     *                  already set
     */
    public void notifyStepComplete(String sessionId, String agentRole, ReasoningStep step) {
        observers.forEach(o -> {
            try {
                o.onStepComplete(sessionId, agentRole, step);
            } catch (Exception e) {
                log.warnf(
                        "ObservabilityManager: observer %s threw on onStepComplete for session %s: %s",
                        ClassUtils.getCleanName(o), sessionId, AgentErrorMessageUtil.safeMessage(e));
            }
        });
    }
}
