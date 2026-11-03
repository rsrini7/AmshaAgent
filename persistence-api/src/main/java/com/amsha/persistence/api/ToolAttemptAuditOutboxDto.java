package com.amsha.persistence.api;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Transfer object representing an audit outbox record per Proposal §12.4.
 */
public record ToolAttemptAuditOutboxDto(
        String eventId,
        String idempotencyKey,
        String workspaceId,
        String sessionId,
        String attemptId,
        String resolutionId,
        ToolAttemptAuditEventKind eventKind,
        String safeMetadataJson,
        Instant createdAt,
        int deliveryAttempts,
        Instant nextAttemptAt,
        String leaseId,
        Instant leaseUntil,
        Instant deliveredAt,
        String deliveredAuditEventId) {

    private static final Pattern CANONICAL_UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public ToolAttemptAuditOutboxDto {
        validateCanonicalUuid(eventId, "eventId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(eventKind, "eventKind must not be null");
        Objects.requireNonNull(safeMetadataJson, "safeMetadataJson must not be null");
        if (safeMetadataJson.isBlank()) {
            throw new IllegalArgumentException("safeMetadataJson must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (deliveryAttempts < 0) {
            throw new IllegalArgumentException("deliveryAttempts must be non-negative: " + deliveryAttempts);
        }
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");

        if (eventKind.isResolutionEvent()) {
            if (resolutionId == null || resolutionId.isBlank()) {
                throw new IllegalArgumentException("resolutionId must not be blank for resolution event: " + eventKind);
            }
            validateCanonicalUuid(resolutionId, "resolutionId");
            if (attemptId != null) {
                throw new IllegalArgumentException("attemptId must be null for resolution event: " + eventKind);
            }
            String expectedKey = workspaceId + ":" + resolutionId + ":" + eventKind.name();
            if (!idempotencyKey.equals(expectedKey)) {
                throw new IllegalArgumentException("idempotencyKey does not match resolution event identity");
            }
        } else {
            if (attemptId == null || attemptId.isBlank()) {
                throw new IllegalArgumentException("attemptId must not be blank for attempt event: " + eventKind);
            }
            validateCanonicalUuid(attemptId, "attemptId");
            if (resolutionId != null) {
                throw new IllegalArgumentException("resolutionId must be null for attempt event: " + eventKind);
            }
            String expectedKey = workspaceId + ":" + attemptId + ":" + eventKind.name();
            if (!idempotencyKey.equals(expectedKey)) {
                throw new IllegalArgumentException("idempotencyKey does not match attempt event identity");
            }
        }

        if ((leaseId == null) != (leaseUntil == null)) {
            throw new IllegalArgumentException("leaseId and leaseUntil must be set or cleared together");
        }
        if (leaseId != null && leaseId.isBlank()) {
            throw new IllegalArgumentException("leaseId must not be blank");
        }
        if ((deliveredAt == null) != (deliveredAuditEventId == null)) {
            throw new IllegalArgumentException("deliveredAt and deliveredAuditEventId must be set together");
        }
        if (deliveredAt != null && leaseId != null) {
            throw new IllegalArgumentException("a delivered outbox event cannot retain an active lease");
        }
        if (deliveredAuditEventId != null) {
            validateCanonicalUuid(deliveredAuditEventId, "deliveredAuditEventId");
        }
    }

    private static void validateCanonicalUuid(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (!CANONICAL_UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must be a canonical lowercase UUID: " + value);
        }
    }

    /**
     * Factory for creating an attempt-scoped outbox record.
     */
    public static ToolAttemptAuditOutboxDto forAttempt(
            String eventId,
            String workspaceId,
            String sessionId,
            String attemptId,
            ToolAttemptAuditEventKind eventKind,
            String safeMetadataJson,
            Instant createdAt) {
        if (eventKind == null || eventKind.isResolutionEvent()) {
            throw new IllegalArgumentException("eventKind must be an attempt event: " + eventKind);
        }
        Objects.requireNonNull(safeMetadataJson, "safeMetadataJson must not be null");
        String idempotencyKey = workspaceId + ":" + attemptId + ":" + eventKind.name();
        return new ToolAttemptAuditOutboxDto(
                eventId,
                idempotencyKey,
                workspaceId,
                sessionId,
                attemptId,
                null,
                eventKind,
                safeMetadataJson,
                createdAt,
                0,
                createdAt,
                null,
                null,
                null,
                null);
    }

    /**
     * Factory for creating a resolution-scoped outbox record.
     */
    public static ToolAttemptAuditOutboxDto forResolution(
            String eventId,
            String workspaceId,
            String sessionId,
            String resolutionId,
            ToolAttemptAuditEventKind eventKind,
            String safeMetadataJson,
            Instant createdAt) {
        if (eventKind == null || !eventKind.isResolutionEvent()) {
            throw new IllegalArgumentException("eventKind must be a resolution event: " + eventKind);
        }
        Objects.requireNonNull(safeMetadataJson, "safeMetadataJson must not be null");
        String idempotencyKey = workspaceId + ":" + resolutionId + ":" + eventKind.name();
        return new ToolAttemptAuditOutboxDto(
                eventId,
                idempotencyKey,
                workspaceId,
                sessionId,
                null,
                resolutionId,
                eventKind,
                safeMetadataJson,
                createdAt,
                0,
                createdAt,
                null,
                null,
                null,
                null);
    }
}
