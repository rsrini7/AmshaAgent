package com.amsha.core.state;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.AdvisoryLockPort;

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
 * through {@link StatefulAgentStore#claimForResume(String, String)},
 * {@link StatefulAgentStore#isBatchResuming(String)}, and
 * {@link StatefulAgentStore#clearBatchResuming(String)}.
 * {@link StatefulAgentStoreImpl} is the only legitimate injector of this guard.
 */
@ApplicationScoped
public class ResumeCoordinationGuard {

    private static final Logger log = Logger.getLogger(ResumeCoordinationGuard.class);

    /**
     * Capture when the mark was set, whether it involves browser tools,
     * the active goal, a coarse activity phase, and the timestamp of the last
     * observed reasoning step so the staleness check reflects genuine progress
     * rather than just the moment the turn started.
     */
    private record ResumeMark(
            Instant startTime, boolean hasBrowserTool, String activeGoal, Instant lastActivityAt, String phase) {}

    /** Sessions currently in the RESUMING state. */
    private final ConcurrentHashMap<String, ResumeMark> resumingBatches = new ConcurrentHashMap<>();

    /**
     * Stale-mark TTL. After N seconds a lingering mark is auto-expired by
     * {@link #isResuming(String)} so a crashed/stuck resume cannot permanently
     * block the session.
     */
    @ConfigProperty(name = "amsha.resumption.stale-mark-seconds", defaultValue = "60")
    long staleResumeSeconds;

    @ConfigProperty(name = "amsha.resumption.playwright-stale-mark-seconds", defaultValue = "300")
    long playwrightStaleMarkSeconds;

    @ConfigProperty(name = "amsha.resume-lock.db-advisory", defaultValue = "false")
    boolean dbAdvisoryEnabled;

    @Inject
    Instance<AdvisoryLockPort> lockPort;

    /**
     * Atomically marks the session as RESUMING.
     *
     * @return {@code true} if this caller is the first to mark it - safe to resume;
     *         {@code false} if another thread/caller already marked it - skip,
     *         already resuming.
     */
    public boolean markResuming(String sessionId) {
        return markResuming(sessionId, false, null, ActivityPhase.RUNNING);
    }

    /**
     * Canonical mark: stores browser-tool flag, active goal, and a coarse activity
     * phase, and stamps both the creation and last-activity timestamps to now.
     */
    public boolean markResuming(String sessionId, boolean hasBrowserTool, String activeGoal, String phase) {
        if (dbAdvisoryEnabled && !lockPort.isUnsatisfied()) {
            isResuming(sessionId);
            int ttl = (int) (hasBrowserTool ? playwrightStaleMarkSeconds : staleResumeSeconds);
            boolean acquired = lockPort.get().tryLock("RESUME:" + sessionId, ttl);
            if (acquired) {
                resumingBatches.put(
                        sessionId, new ResumeMark(Instant.now(), hasBrowserTool, activeGoal, Instant.now(), phase));
            }
            return acquired;
        }
        isResuming(sessionId);
        return resumingBatches.putIfAbsent(
                        sessionId, new ResumeMark(Instant.now(), hasBrowserTool, activeGoal, Instant.now(), phase))
                == null;
    }

    /**
     * Refreshes the last-activity timestamp (and optionally the phase) of an
     * existing resume mark so it does not expire while the agent is genuinely
     * making progress. No-op if no mark is present. Refreshes both the exact
     * session id and any parent session (delegate runs keep the top-level mark
     * fresh).
     *
     * <p><strong>Parent Phase Propagation Contract:</strong>
     * When a delegated child reports activity, propagating its active phase to the
     * parent mark maintains end-to-end conversation liveness across single-session
     * channels (e.g. Telegram typing actions and status polling). As a result, the
     * parent session's status line reflects the delegated subagent's immediate tool
     * action (e.g., {@code running search}) while the delegation is underway.
     */
    public void touch(String sessionId) {
        touch(sessionId, null);
    }

    public void touch(String sessionId, String phase) {
        if (sessionId == null) return;
        refreshMark(sessionId, phase);
        String parent = SessionTree.parentOf(sessionId).orElse(null);
        if (parent != null) {
            refreshMark(parent, phase);
        }
    }

    private void refreshMark(String key, String phase) {
        ResumeMark updated = resumingBatches.computeIfPresent(
                key,
                (_, mark) -> new ResumeMark(
                        mark.startTime(),
                        mark.hasBrowserTool(),
                        mark.activeGoal(),
                        Instant.now(),
                        phase != null ? phase : mark.phase()));
        if (updated != null && dbAdvisoryEnabled && !lockPort.isUnsatisfied()) {
            refreshDbLock(key, updated.hasBrowserTool());
        }
    }

    private void refreshDbLock(String sessionId, boolean hasBrowserTool) {
        try {
            int ttl = (int) (hasBrowserTool ? playwrightStaleMarkSeconds : staleResumeSeconds);
            boolean renewed = lockPort.get().renew("RESUME:" + sessionId, ttl);
            if (!renewed) {
                log.debugf("Failed to renew DB advisory lock for session=%s (not held or expired)", sessionId);
            }
        } catch (Exception e) {
            log.debugf("Failed to refresh DB advisory lock for session=%s: %s", sessionId, LogUtil.rootMessage(e));
        }
    }

    /**
     * Returns {@code true} if the session is currently marked as RESUMING.
     *
     * <p>Staleness is judged by the time of the <em>last observed progress</em>
     * (a reasoning step / activity refresh), not the moment the turn started, so a
     * long but healthy turn is never reported as idle. Auto-expires stale marks
     * after the appropriate TTL to prevent permanently blocked sessions after a
     * crash or genuine hang.
     */
    public boolean isResuming(String sessionId) {
        ResumeMark mark = resumingBatches.get(sessionId);
        if (mark != null) {
            long ttl = mark.hasBrowserTool() ? playwrightStaleMarkSeconds : staleResumeSeconds;
            if (Duration.between(mark.lastActivityAt(), Instant.now()).toSeconds() > ttl) {
                log.warnf(
                        "ResumeCoordinationGuard: stale resume mark auto-expired for session=%s "
                                + "(no progress for %s, ttl=%ds, browser=%b)",
                        sessionId, Duration.between(mark.lastActivityAt(), Instant.now()), ttl, mark.hasBrowserTool());
                clearResuming(sessionId);
                return false;
            }
            return true;
        }

        if (dbAdvisoryEnabled && !lockPort.isUnsatisfied()) {
            return lockPort.get().isLocked("RESUME:" + sessionId);
        }
        return false;
    }

    /**
     * Returns the goal and coarse activity phase (e.g. {@code TOOL:read_file}) of
     * the running/resuming session, if any. Empty once the mark expires.
     *
     * <p>Goal and phase are returned together because every consumer renders them
     * together; exposing them separately invites the two to drift apart.
     */
    public Optional<ActiveWork> getActiveWork(String sessionId) {
        if (!isResuming(sessionId)) {
            return Optional.empty();
        }
        ResumeMark mark = resumingBatches.get(sessionId);
        return mark == null ? Optional.empty() : Optional.of(new ActiveWork(mark.activeGoal(), mark.phase()));
    }

    public Optional<String> getActiveGoal(String sessionId) {
        return getActiveWork(sessionId).map(ActiveWork::goal);
    }

    public Optional<String> getActivePhase(String sessionId) {
        return getActiveWork(sessionId).map(ActiveWork::phase);
    }

    /**
     * Clears the RESUMING flag for the session.
     *
     * <p><strong>§3.4 (MUST):</strong> This MUST be called in the {@code finally}
     * block of the resume path and by {@code WorkspaceManager.terminateSession()}.
     * Never suppress or swallow the call.
     */
    public void clearResuming(String sessionId) {
        if (dbAdvisoryEnabled && !lockPort.isUnsatisfied()) {
            lockPort.get().unlock("RESUME:" + sessionId);
        }
        resumingBatches.remove(sessionId);
    }
}
