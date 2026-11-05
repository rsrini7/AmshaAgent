package com.amsha.core.tool.attempt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.ToolAttemptAuditEventKind;
import com.amsha.persistence.api.ToolAttemptAuditOutboxDto;
import com.amsha.persistence.api.ToolAttemptStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolAttemptAuditOutboxRelayTest {
    private static final String WORKSPACE_ID = "workspace-1";
    private static final String SESSION_ID = "session-1";
    private static final String ATTEMPT_ID = "00000000-0000-0000-0000-000000000001";
    private static final String EVENT_ID = "00000000-0000-0000-0000-000000000002";
    private static final String AUDIT_EVENT_ID = "00000000-0000-0000-0000-000000000003";

    private final Instance<ToolAttemptStorePort> stores = mock();
    private final ToolAttemptStorePort store = mock();
    private final AuditService auditService = mock();
    private final ToolAttemptAuditOutboxRelay relay = new ToolAttemptAuditOutboxRelay(stores, auditService);

    @BeforeEach
    void setUp() {
        when(stores.isUnsatisfied()).thenReturn(false);
        when(stores.isAmbiguous()).thenReturn(false);
        when(stores.get()).thenReturn(store);
    }

    @Test
    void requiredDeliveryAcknowledgesTheHmacAuditEventId() {
        Instant createdAt = Instant.parse("2026-11-05T00:00:00Z");
        ToolAttemptAuditOutboxDto pending = pendingEvent(createdAt);
        AtomicInteger reads = new AtomicInteger();
        when(store.findOutboxEvent(WORKSPACE_ID, EVENT_ID))
                .thenAnswer(_ ->
                        reads.getAndIncrement() == 0 ? Optional.of(pending) : Optional.of(deliveredEvent(createdAt)));
        when(store.leaseOutboxEvents(eq(WORKSPACE_ID), anyString(), any(Instant.class), anyInt()))
                .thenAnswer(invocation -> List.of(leasedEvent(pending, invocation.getArgument(1), createdAt)));
        when(auditService.recordMandatoryWithReceipt(
                        SESSION_ID,
                        null,
                        null,
                        SecurityEventType.TOOL_ATTEMPT_ADMITTED,
                        "{}",
                        pending.idempotencyKey()))
                .thenReturn(AUDIT_EVENT_ID);
        when(store.acknowledgeOutboxEvent(
                        eq(WORKSPACE_ID), eq(EVENT_ID), anyString(), eq(AUDIT_EVENT_ID), any(Instant.class)))
                .thenReturn(true);

        assertEquals(AUDIT_EVENT_ID, relay.deliverRequired(WORKSPACE_ID, EVENT_ID));

        verify(store)
                .acknowledgeOutboxEvent(
                        eq(WORKSPACE_ID), eq(EVENT_ID), anyString(), eq(AUDIT_EVENT_ID), any(Instant.class));
    }

    @Test
    void failedMandatoryAuditIsRescheduledAndRequiredDeliveryFailsClosed() {
        Instant createdAt = Instant.parse("2026-11-05T00:00:00Z");
        ToolAttemptAuditOutboxDto pending = pendingEvent(createdAt);
        when(store.findOutboxEvent(WORKSPACE_ID, EVENT_ID)).thenReturn(Optional.of(pending));
        when(store.leaseOutboxEvents(eq(WORKSPACE_ID), anyString(), any(Instant.class), anyInt()))
                .thenAnswer(invocation -> List.of(leasedEvent(pending, invocation.getArgument(1), createdAt)));
        when(auditService.recordMandatoryWithReceipt(
                        SESSION_ID,
                        null,
                        null,
                        SecurityEventType.TOOL_ATTEMPT_ADMITTED,
                        "{}",
                        pending.idempotencyKey()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        when(store.rescheduleOutboxEvent(eq(WORKSPACE_ID), eq(EVENT_ID), anyString(), any(Instant.class), eq(1)))
                .thenReturn(true);

        assertThrows(IllegalStateException.class, () -> relay.deliverRequired(WORKSPACE_ID, EVENT_ID));

        verify(store).rescheduleOutboxEvent(eq(WORKSPACE_ID), eq(EVENT_ID), anyString(), any(Instant.class), eq(1));
    }

    private static ToolAttemptAuditOutboxDto pendingEvent(Instant createdAt) {
        return ToolAttemptAuditOutboxDto.forAttempt(
                EVENT_ID,
                WORKSPACE_ID,
                SESSION_ID,
                ATTEMPT_ID,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                "{}",
                createdAt);
    }

    private static ToolAttemptAuditOutboxDto leasedEvent(
            ToolAttemptAuditOutboxDto event, String leaseId, Instant createdAt) {
        return new ToolAttemptAuditOutboxDto(
                event.eventId(),
                event.idempotencyKey(),
                event.workspaceId(),
                event.sessionId(),
                event.attemptId(),
                event.resolutionId(),
                event.eventKind(),
                event.safeMetadataJson(),
                event.createdAt(),
                event.deliveryAttempts(),
                event.nextAttemptAt(),
                leaseId,
                createdAt.plusSeconds(120),
                null,
                null);
    }

    private static ToolAttemptAuditOutboxDto deliveredEvent(Instant createdAt) {
        return new ToolAttemptAuditOutboxDto(
                EVENT_ID,
                WORKSPACE_ID + ":" + ATTEMPT_ID + ":ATTEMPT_ADMITTED",
                WORKSPACE_ID,
                SESSION_ID,
                ATTEMPT_ID,
                null,
                ToolAttemptAuditEventKind.ATTEMPT_ADMITTED,
                "{}",
                createdAt,
                0,
                createdAt,
                null,
                null,
                createdAt.plusSeconds(1),
                AUDIT_EVENT_ID);
    }
}
