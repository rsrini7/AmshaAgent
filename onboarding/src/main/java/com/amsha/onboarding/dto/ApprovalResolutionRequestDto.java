package com.amsha.onboarding.dto;

/**
 * Request payload to approve or deny a pending onboarding approval.
 */
public record ApprovalResolutionRequestDto(boolean approved, String reason) {}
