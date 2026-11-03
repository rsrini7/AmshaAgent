package com.amsha.persistence.api;

import java.util.Objects;

/**
 * Outcome of an attempt state transition per Proposal §12.4.
 */
public record ToolAttemptTransitionResult(TransitionStatus status, ToolAttemptDto updatedAttempt, String message) {

    /**
     * Transition status outcomes per §12.4.
     */
    public enum TransitionStatus {
        APPLIED,
        STALE,
        CONFLICT
    }

    public ToolAttemptTransitionResult {
        Objects.requireNonNull(status, "status must not be null");
        if (status == TransitionStatus.APPLIED) {
            Objects.requireNonNull(updatedAttempt, "updatedAttempt must not be null for APPLIED status");
            if (message != null) {
                throw new IllegalArgumentException("message must be null for APPLIED status");
            }
        } else {
            if (updatedAttempt != null) {
                throw new IllegalArgumentException("updatedAttempt must be null for status: " + status);
            }
            Objects.requireNonNull(message, "message must not be null for status: " + status);
            if (message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank for status: " + status);
            }
        }
    }

    public static ToolAttemptTransitionResult applied(ToolAttemptDto updatedAttempt) {
        return new ToolAttemptTransitionResult(TransitionStatus.APPLIED, updatedAttempt, null);
    }

    public static ToolAttemptTransitionResult stale(String message) {
        return new ToolAttemptTransitionResult(TransitionStatus.STALE, null, message);
    }

    public static ToolAttemptTransitionResult conflict(String message) {
        return new ToolAttemptTransitionResult(TransitionStatus.CONFLICT, null, message);
    }
}
