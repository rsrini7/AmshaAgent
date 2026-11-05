package com.amsha.core.tool.attempt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptStorePort;

import org.jboss.logging.Logger;

/** Delivers durable tool-attempt audit events to the mandatory HMAC audit chain. */
@ApplicationScoped
public class ToolAttemptAuditOutboxRelay {
    private static final Logger log = Logger.getLogger(ToolAttemptAuditOutboxRelay.class);
    private static final int BATCH_SIZE = 64;
    private static final int MAX_SYNC_BATCHES = 16;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);

    private final Instance<ToolAttemptStorePort> stores;
    private final AuditService auditService;

    @Inject
    public ToolAttemptAuditOutboxRelay(Instance<ToolAttemptStorePort> stores, AuditService auditService) {
        this.stores = stores;
        this.auditService = auditService;
    }

    /** Delivers pending events for every known workspace using its active workspace context. */
    public int sweepAll() {
        ToolAttemptStorePort store = requireStore();
        Set<String> workspaceIds = store.listWorkspaceIds();
        int delivered = 0;
        for (String workspaceId : workspaceIds) {
            try {
                delivered += WorkspaceContext.callWithActiveWorkspace(
                        workspaceId, () -> deliverPendingInWorkspace(store, workspaceId));
            } catch (Exception e) {
                log.errorf(
                        LogUtil.relevantThrowable(e),
                        "[ToolAttemptAuditOutbox] Workspace delivery failed for %s: %s",
                        workspaceId,
                        LogUtil.rootMessage(e));
            }
        }
        return delivered;
    }

    /**
     * Delivers and acknowledges the specified outbox event before its caller crosses a governed
     * execution boundary. A missing event or failed delivery is a fail-closed error.
     */
    public String deliverRequired(String workspaceId, String eventId) {
        ToolAttemptStorePort store = requireStore();
        try {
            return WorkspaceContext.callWithActiveWorkspace(
                    workspaceId, () -> deliverRequiredInWorkspace(store, workspaceId, eventId));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Required tool-attempt audit delivery failed.", e);
        }
    }

    private String deliverRequiredInWorkspace(ToolAttemptStorePort store, String workspaceId, String eventId) {
        ToolAttemptAuditOutboxDto target = store.findOutboxEvent(workspaceId, eventId)
                .orElseThrow(() -> new IllegalStateException("Required tool-attempt audit event is missing."));
        if (target.deliveredAt() != null) {
            return target.deliveredAuditEventId();
        }

        for (int batchNumber = 0; batchNumber < MAX_SYNC_BATCHES; batchNumber++) {
            List<ToolAttemptAuditOutboxDto> batch = leaseBatch(store, workspaceId);
            if (batch.isEmpty()) {
                break;
            }
            for (ToolAttemptAuditOutboxDto event : batch) {
                boolean delivered = deliverLeased(store, event);
                if (eventId.equals(event.eventId())) {
                    if (!delivered) {
                        throw new IllegalStateException("Required tool-attempt audit event delivery failed.");
                    }
                    return store.findOutboxEvent(workspaceId, eventId)
                            .filter(item -> item.deliveredAt() != null)
                            .map(ToolAttemptAuditOutboxDto::deliveredAuditEventId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Required tool-attempt audit event acknowledgement is missing."));
                }
            }
            Optional<ToolAttemptAuditOutboxDto> current = store.findOutboxEvent(workspaceId, eventId);
            if (current.isPresent() && current.get().deliveredAt() != null) {
                return current.get().deliveredAuditEventId();
            }
        }
        throw new IllegalStateException("Required tool-attempt audit event could not be leased and acknowledged.");
    }

    private int deliverPendingInWorkspace(ToolAttemptStorePort store, String workspaceId) {
        int delivered = 0;
        for (int batchNumber = 0; batchNumber < MAX_SYNC_BATCHES; batchNumber++) {
            List<ToolAttemptAuditOutboxDto> batch = leaseBatch(store, workspaceId);
            for (ToolAttemptAuditOutboxDto event : batch) {
                if (deliverLeased(store, event)) {
                    delivered++;
                }
            }
            if (batch.size() < BATCH_SIZE) {
                return delivered;
            }
        }
        return delivered;
    }

    private List<ToolAttemptAuditOutboxDto> leaseBatch(ToolAttemptStorePort store, String workspaceId) {
        Instant now = Instant.now();
        return store.leaseOutboxEvents(workspaceId, UUID.randomUUID().toString(), now.plus(LEASE_DURATION), BATCH_SIZE);
    }

    private boolean deliverLeased(ToolAttemptStorePort store, ToolAttemptAuditOutboxDto event) {
        try {
            String auditEventId = auditService.recordMandatoryWithReceipt(
                    event.sessionId(),
                    null,
                    null,
                    securityEventType(event.eventKind()),
                    event.safeMetadataJson(),
                    event.idempotencyKey());
            if (auditEventId == null || auditEventId.isBlank()) {
                throw new IllegalStateException("Mandatory audit append returned no durable event ID.");
            }
            if (store.acknowledgeOutboxEvent(
                    event.workspaceId(), event.eventId(), event.leaseId(), auditEventId, Instant.now())) {
                return true;
            }
            return store.findOutboxEvent(event.workspaceId(), event.eventId())
                    .filter(current -> current.deliveredAt() != null)
                    .isPresent();
        } catch (RuntimeException e) {
            return rescheduleAfterFailure(store, event, e);
        }
    }

    private boolean rescheduleAfterFailure(
            ToolAttemptStorePort store, ToolAttemptAuditOutboxDto event, RuntimeException failure) {
        int attempts = event.deliveryAttempts() + 1;
        Instant retryAt = Instant.now().plus(retryDelay(attempts));
        boolean rescheduled =
                store.rescheduleOutboxEvent(event.workspaceId(), event.eventId(), event.leaseId(), retryAt, attempts);
        if (!rescheduled) {
            Optional<ToolAttemptAuditOutboxDto> current = store.findOutboxEvent(event.workspaceId(), event.eventId());
            if (current.filter(item -> item.deliveredAt() != null).isPresent()) {
                return true;
            }
            log.warnf(
                    LogUtil.relevantThrowable(failure),
                    "[ToolAttemptAuditOutbox] Delivery and reschedule failed for event %s: %s",
                    event.eventId(),
                    LogUtil.rootMessage(failure));
            return false;
        }
        log.warnf(
                LogUtil.relevantThrowable(failure),
                "[ToolAttemptAuditOutbox] Delivery deferred for event %s: %s",
                event.eventId(),
                LogUtil.rootMessage(failure));
        return false;
    }

    private static Duration retryDelay(int attempts) {
        int exponent = Math.min(Math.max(attempts, 1), 8);
        Duration delay = Duration.ofSeconds(1L << exponent);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private ToolAttemptStorePort requireStore() {
        if (stores.isUnsatisfied() || stores.isAmbiguous()) {
            throw new IllegalStateException("Tool-attempt storage is unavailable or ambiguous.");
        }
        return stores.get();
    }

    private static SecurityEventType securityEventType(ToolAttemptAuditEventKind eventKind) {
        return switch (eventKind) {
            case ATTEMPT_ADMITTED -> SecurityEventType.TOOL_ATTEMPT_ADMITTED;
            case LAUNCH_REQUESTED -> SecurityEventType.TOOL_LAUNCH_REQUESTED;
            case ATTEMPT_SETTLED -> SecurityEventType.TOOL_ATTEMPT_SETTLED;
            case ACTION_LOCKED -> SecurityEventType.TOOL_ACTION_LOCKED;
            case ACTION_RESOLUTION_AUTHORIZED -> SecurityEventType.TOOL_ACTION_RESOLUTION_AUTHORIZED;
            case ACTION_RESOLVED -> SecurityEventType.TOOL_ACTION_RESOLVED;
        };
    }
}
