package com.amsha.persistence.api;

/**
 * Lifecycle state of an onboarding draft.
 */
public enum OnboardingDraftState {
    DRAFT,
    WAITING_APPROVAL,
    RESUMABLE,
    COMMITTING,
    COMPLETED,
    CANCELLED,
    FAILED
}
