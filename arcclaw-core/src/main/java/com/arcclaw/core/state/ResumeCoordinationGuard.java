package com.arcclaw.core.state;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * JVM-local guard that prevents duplicate concurrent resume attempts for the
 * same session (double-resume guard, §3.4 / §7.29).
 *
 * <p><strong>JVM-local limitation (documented per §7.29):</strong>
 * The {@code resumingBatches} map is in-process only. In a multi-node deployment
 * this guard does NOT protect against two pods racing to resume the same session.
 * See invariant XB1 for the distributed lock upgrade path when that becomes needed.
 *
 * <p><strong>Lifecycle contract (§3.4 / §7.29 - MUST):</strong>
 * <ul>
 *   <li>{@link #markResuming(String)} is called at the entry of the resume path.</li>
 *   <li>{@link #clearResuming(String)} MUST be called in the {@code finally} block
 *       of the resume path AND by {@code WorkspaceManager.terminateSession()}.</li>
 * </ul>
 *
 * <p><strong>Public API contract (§3.4 / §7.29 - MUST):</strong>
 * External callers MUST NOT inject this class directly. They must always call
 * through {@link StatefulAgentStore#markBatchResuming(String)},
 * {@link StatefulAgentStore#isBatchResuming(String)}, and
 * {@link StatefulAgentStore#clearBatchResuming(String)}.
 * {@link StatefulAgentStoreImpl} is the only legitimate injector of this guard.
 */
@ApplicationScoped
public class ResumeCoordinationGuard {

    private static final Logger log = Logger.getLogger(ResumeCoordinationGuard.class);

    /** 
     * Capture both when the mark was set and whether it involves browser tools
     * so we can apply the correct TTL consistently.
     */
    private record ResumeMark(Instant startTime, boolean hasBrowserTool) {}

    /** Sessions currently in the RESUMING state. */
    private final ConcurrentHashMap<String, ResumeMark> resumingBatches = new ConcurrentHashMap<>();

    /**
     * Stale-mark TTL. After N seconds a lingering mark is auto-expired by
     * {@link #isResuming(String)} so a crashed/stuck resume cannot permanently
     * block the session.
     */
    @ConfigProperty(name = "arcclaw.resumption.stale-mark-seconds", defaultValue = "60")
    long staleResumeSeconds;

    @ConfigProperty(name = "arcclaw.resumption.playwright-stale-mark-seconds", defaultValue = "300")
    long playwrightStaleMarkSeconds;

    /**
     * Atomically marks the session as RESUMING.
     *
     * @return {@code true} if this caller is the first to mark it - safe to resume;
     *         {@code false} if another thread/caller already marked it - skip,
     *         already resuming.
     */
    public boolean markResuming(String sessionId) {
        return markResuming(sessionId, false);
    }

    /**
     * Overloaded to store the browser-tool flag.
     */
    public boolean markResuming(String sessionId, boolean hasBrowserTool) {
        return resumingBatches.putIfAbsent(sessionId, new ResumeMark(Instant.now(), hasBrowserTool)) == null;
    }

    /**
     * Returns {@code true} if the session is currently marked as RESUMING.
     *
     * <p>Auto-expires stale marks after the appropriate TTL to prevent 
     * permanently blocked sessions after a crash.
     */
    public boolean isResuming(String sessionId) {
        ResumeMark mark = resumingBatches.get(sessionId);
        if (mark == null) return false;

        long ttl = mark.hasBrowserTool() ? playwrightStaleMarkSeconds : staleResumeSeconds;

        if (Duration.between(mark.startTime(), Instant.now()).toSeconds() > ttl) {
            log.warnf("ResumeCoordinationGuard: stale resume mark auto-expired for session=%s "
                    + "(marked %s ago, ttl=%ds, browser=%b)", sessionId, 
                    Duration.between(mark.startTime(), Instant.now()), ttl, mark.hasBrowserTool());
            resumingBatches.remove(sessionId);
            return false;
        }
        return true;
    }

    /**
     * Clears the RESUMING flag for the session.
     *
     * <p><strong>§3.4 (MUST):</strong> This MUST be called in the {@code finally}
     * block of the resume path and by {@code WorkspaceManager.terminateSession()}.
     * Never suppress or swallow the call.
     */
    public void clearResuming(String sessionId) {
        resumingBatches.remove(sessionId);
    }
}
