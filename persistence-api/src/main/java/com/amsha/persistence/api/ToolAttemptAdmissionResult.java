package com.amsha.persistence.api;

import java.util.Objects;

/**
 * Outcome of an attempt admission call per Proposal §12.4.
 */
public record ToolAttemptAdmissionResult(
        AdmissionStatus status,
        ToolAttemptDto attempt,
        ToolAttemptAuditOutboxDto admissionOutboxEvent,
        String message) {

    /**
     * Admission status outcomes per §12.4.
     */
    public enum AdmissionStatus {
        CREATED,
        IDENTICAL_EXISTING,
        AT_MOST_ONCE_EXISTING,
        CONFLICT
    }

    public ToolAttemptAdmissionResult {
        Objects.requireNonNull(status, "status must not be null");
        switch (status) {
            case CREATED -> {
                Objects.requireNonNull(attempt, "attempt must not be null for CREATED status");
                Objects.requireNonNull(
                        admissionOutboxEvent, "admissionOutboxEvent must not be null for CREATED status");
                if (message != null) {
                    throw new IllegalArgumentException("message must be null for CREATED status");
                }
            }
            case IDENTICAL_EXISTING -> {
                Objects.requireNonNull(attempt, "attempt must not be null for status: " + status);
                if (attempt.status() == ToolAttemptStatus.ADMITTED) {
                    Objects.requireNonNull(
                            admissionOutboxEvent,
                            "admissionOutboxEvent must be present while an identical attempt remains ADMITTED");
                }
                if (message != null) {
                    throw new IllegalArgumentException("message must be null for status: " + status);
                }
            }
            case AT_MOST_ONCE_EXISTING -> {
                Objects.requireNonNull(attempt, "attempt must not be null for status: " + status);
                if (attempt.idempotencyModeSnapshot() != ToolAttemptIdempotencyMode.AT_MOST_ONCE
                        || !attempt.status().isReplayableAtMostOnceOutcome()) {
                    throw new IllegalArgumentException(
                            "AT_MOST_ONCE_EXISTING requires a replayable terminal AT_MOST_ONCE attempt");
                }
                if (admissionOutboxEvent != null) {
                    throw new IllegalArgumentException("admissionOutboxEvent must be null for AT_MOST_ONCE_EXISTING");
                }
                if (message != null) {
                    throw new IllegalArgumentException("message must be null for status: " + status);
                }
            }
            case CONFLICT -> {
                if (attempt != null || admissionOutboxEvent != null) {
                    throw new IllegalArgumentException("attempt and admissionOutboxEvent must be null for CONFLICT");
                }
                Objects.requireNonNull(message, "message must not be null for CONFLICT status");
                if (message.isBlank()) {
                    throw new IllegalArgumentException("message must not be blank for CONFLICT status");
                }
            }
        }
        if (attempt != null && admissionOutboxEvent != null) {
            if (!attempt.workspaceId().equals(admissionOutboxEvent.workspaceId())
                    || !attempt.sessionId().equals(admissionOutboxEvent.sessionId())
                    || !attempt.attemptId().equals(admissionOutboxEvent.attemptId())
                    || admissionOutboxEvent.eventKind() != ToolAttemptAuditEventKind.ATTEMPT_ADMITTED) {
                throw new IllegalArgumentException("admission outbox event must match the returned attempt");
            }
        }
    }

    public static ToolAttemptAdmissionResult created(
            ToolAttemptDto attempt, ToolAttemptAuditOutboxDto admissionOutboxEvent) {
        return new ToolAttemptAdmissionResult(AdmissionStatus.CREATED, attempt, admissionOutboxEvent, null);
    }

    public static ToolAttemptAdmissionResult identicalExisting(
            ToolAttemptDto attempt, ToolAttemptAuditOutboxDto admissionOutboxEvent) {
        return new ToolAttemptAdmissionResult(AdmissionStatus.IDENTICAL_EXISTING, attempt, admissionOutboxEvent, null);
    }

    public static ToolAttemptAdmissionResult atMostOnceExisting(ToolAttemptDto attempt) {
        return new ToolAttemptAdmissionResult(AdmissionStatus.AT_MOST_ONCE_EXISTING, attempt, null, null);
    }

    public static ToolAttemptAdmissionResult conflict(String message) {
        return new ToolAttemptAdmissionResult(AdmissionStatus.CONFLICT, null, null, message);
    }
}
