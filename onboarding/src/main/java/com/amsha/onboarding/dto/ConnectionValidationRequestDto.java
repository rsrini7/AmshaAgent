package com.amsha.onboarding.dto;

/**
 * Request payload to validate an onboarding connection.
 */
public record ConnectionValidationRequestDto(int connectionIndex, boolean probeNetwork, boolean requestApproval) {}
