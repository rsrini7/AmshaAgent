package com.amsha.onboarding.dto;

/**
 * Result of resolving an onboarding approval request.
 */
public record ApprovalResolutionResponseDto(String approvalId, String status, boolean verified, String message) {}
