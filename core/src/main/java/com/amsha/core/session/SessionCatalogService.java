package com.amsha.core.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.core.event.DomainEventBus;
import com.amsha.core.event.EventTypeCatalog;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.pipeline.SessionIdResolver;
import com.amsha.core.reflex.ApprovalNotificationService;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;
import com.amsha.security.api.AutonomyLevel;

import org.jboss.logging.Logger;

/** Coordinates durable catalog operations without opening a wider transaction. */
@ApplicationScoped
public class SessionCatalogService {
    private final SessionCatalogStore store;
    private final SessionIdResolver legacyResolver;
    private final SessionHeartbeatTracker heartbeatTracker;
    private final ApprovalNotificationService approvalNotificationService;

    @Inject
    DomainEventBus eventBus;

    @Inject
    public SessionCatalogService(
            SessionCatalogStore store,
            SessionIdResolver legacyResolver,
            SessionHeartbeatTracker heartbeatTracker,
            ApprovalNotificationService approvalNotificationService) {
        this.store = store;
        this.legacyResolver = legacyResolver;
        this.heartbeatTracker = heartbeatTracker;
        this.approvalNotificationService = approvalNotificationService;
    }

    public String resolveOrCreateSession(String channelType, String channelId, String ownerKey) {
        String canonical = canonical(channelType);
        return store.resolveActive(ownerKey, canonical, channelId)
                .map(SessionCatalogEntry::sessionId)
                .orElseGet(() -> {
                    String legacy = legacyResolver.resolveOrCreate(channelType, channelId, ownerKey);
                    activate(legacy, ownerKey, canonical, channelId);
                    return legacy;
                });
    }

    public String createSession(String ownerKey, String channelType, String channelId) {
        String sessionId = UUID.randomUUID().toString();
        activate(sessionId, ownerKey, canonical(channelType), channelId);
        return sessionId;
    }

    public String registerOrResumeSession(String targetId, String ownerKey, String channelType, String channelId) {
        String canonical = canonical(channelType);
        Optional<SessionCatalogEntry> existing = store.bySessionId(targetId);
        if (existing.isPresent()) {
            return resume(targetId, ownerKey, channelType, channelId);
        } else {
            activate(targetId, ownerKey, canonical, channelId);
            return targetId;
        }
    }

    public String rotate(String channelType, String channelId, String ownerKey, String oldSessionId) {
        String canonical = canonical(channelType);
        String nextSessionId = UUID.randomUUID().toString();

        // 1. Atomic Store Operation (Rollback Boundary)
        Instant now = Instant.now();
        SessionCatalogEntry newActive = new SessionCatalogEntry(
                UUID.randomUUID().toString(),
                ownerKey,
                canonical,
                channelId,
                nextSessionId,
                null,
                null,
                now,
                now,
                0,
                true,
                "ACTIVE");
        store.activateNew(newActive);

        // 2. External HITL State (Best Effort)
        try {
            if (approvalNotificationService != null) {
                approvalNotificationService.flushPendingNotifications(channelId);
            }
        } catch (Exception e) {
            Logger.getLogger(SessionCatalogService.class)
                    .warn("Failed to flush pending notifications during rotation", LogUtil.relevantThrowable(e));
        }

        // 3. In-Memory Cache (Guaranteed)
        heartbeatTracker.recordHeartbeat(nextSessionId, AutonomyLevel.SUPERVISED);
        heartbeatTracker.tryBindOwner(nextSessionId, ownerKey);

        if (eventBus != null) {
            try {
                eventBus.publish(
                        EventTypeCatalog.SESSION_ROTATED,
                        new SessionRotatedEvent(oldSessionId, nextSessionId, ownerKey, canonical));
            } catch (Exception e) {
                Logger.getLogger(SessionCatalogService.class)
                        .warn("Failed to publish SessionRotatedEvent", LogUtil.relevantThrowable(e));
            }
        }

        return nextSessionId;
    }

    public String resume(String targetId, String ownerKey, String channelType, String channelId) {
        String canonical = canonical(channelType);
        SessionCatalogEntry entry =
                store.bySessionId(targetId).orElseThrow(() -> new IllegalArgumentException("Unknown session"));
        if (!entry.ownerKey().equals(ownerKey)
                || !entry.channelType().equals(canonical)
                || !entry.channelId().equals(channelId))
            throw new IllegalArgumentException("Session is outside this channel scope");
        if ("ARCHIVED".equals(entry.status()))
            throw new IllegalArgumentException("Archived sessions cannot be resumed");
        store.setActive(targetId, true);
        heartbeatTracker.recordHeartbeat(targetId, AutonomyLevel.SUPERVISED);
        heartbeatTracker.tryBindOwner(targetId, ownerKey);
        return targetId;
    }

    public List<SessionCatalogEntry> listScoped(String ownerKey, String channelType, String channelId) {
        return store.listByScope(ownerKey, canonical(channelType), channelId);
    }

    public Optional<SessionCatalogEntry> latestScoped(String ownerKey, String channelType, String channelId) {
        return store.latestByScope(ownerKey, canonical(channelType), channelId);
    }

    /**
     * Returns the latest session that is not archived — i.e. one that can be
     * safely passed to {@link #resume}. CLI {@code --last-session} uses this so
     * that an archived-only scope correctly falls back to a fresh session.
     */
    public Optional<SessionCatalogEntry> latestResumableScoped(String ownerKey, String channelType, String channelId) {
        return store.latestByScope(ownerKey, canonical(channelType), channelId)
                .filter(e -> !"ARCHIVED".equals(e.status()));
    }

    public void touch(String id, String ownerKey, String channelType, String channelId, String role, String preview) {
        store.touch(id, ownerKey, canonical(channelType), channelId, role, preview);
    }

    private void activate(String id, String ownerKey, String channelType, String channelId) {
        Instant now = Instant.now();
        store.activateNew(new SessionCatalogEntry(
                UUID.randomUUID().toString(),
                ownerKey,
                channelType,
                channelId,
                id,
                null,
                null,
                now,
                now,
                0,
                true,
                "ACTIVE"));
        heartbeatTracker.recordHeartbeat(id, AutonomyLevel.SUPERVISED);
        heartbeatTracker.tryBindOwner(id, ownerKey);
    }

    private static String canonical(String channelType) {
        return channelType.toLowerCase(Locale.ROOT);
    }
}
