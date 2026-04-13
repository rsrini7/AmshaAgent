package com.arcclaw.core.persistence;

import java.util.concurrent.CompletableFuture;
import com.arcclaw.core.audit.SecurityEventType;

/**
 * Represents a unit of work to be persisted by the {@link AsyncPersistenceEngine}.
 *
 * <p>Two tiers:
 * <ul>
 *   <li>{@code CRITICAL} — caller blocks on {@code future} until the background
 *       thread confirms the write. Used for mandatory security events.</li>
 *   <li>{@code TELEMETRY} — fire-and-forget: {@code future} is {@code null},
 *       caller does not block.</li>
 * </ul>
 */
public final class PersistenceEvent {

    public enum Tier { CRITICAL, TELEMETRY }

    private final Tier tier;
    private final String sessionId;
    private final SecurityEventType eventType;
    private final Runnable dbAction;

    /**
     * For CRITICAL events only — the caller joins on this future.
     * {@code null} for TELEMETRY events.
     */
    private final CompletableFuture<Void> future;

    private PersistenceEvent(Tier tier,
                             String sessionId,
                             SecurityEventType eventType,
                             Runnable dbAction,
                             CompletableFuture<Void> future) {
        this.tier = tier;
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.dbAction = dbAction;
        this.future = future;
    }

    /** Create a blocking CRITICAL event. The returned future must be joined by the caller. */
    public static PersistenceEvent critical(String sessionId,
                                            SecurityEventType eventType,
                                            Runnable dbAction) {
        return new PersistenceEvent(Tier.CRITICAL, sessionId, eventType, dbAction, new CompletableFuture<>());
    }

    /** Create a fire-and-forget TELEMETRY event. */
    public static PersistenceEvent telemetry(String sessionId,
                                              SecurityEventType eventType,
                                              Runnable dbAction) {
        return new PersistenceEvent(Tier.TELEMETRY, sessionId, eventType, dbAction, null);
    }

    public Tier getTier() { return tier; }
    public String getSessionId() { return sessionId; }
    public SecurityEventType getEventType() { return eventType; }
    public Runnable getDbAction() { return dbAction; }
    public CompletableFuture<Void> getFuture() { return future; }
}
