package com.amsha.onboarding.dto;

/**
 * Result of connection validation tier or approval status.
 */
public record ConnectionValidationResponseDto(
        String tier, String status, String approvalId, String summary, boolean valid, String safeErrors) {

    public static ConnectionValidationResponseDto formatValid() {
        return new ConnectionValidationResponseDto("FORMAT_VALID", "OK", null, null, true, null);
    }

    public static ConnectionValidationResponseDto formatError(String errors) {
        return new ConnectionValidationResponseDto("FORMAT_VALID", "ERROR", null, null, false, errors);
    }

    public static ConnectionValidationResponseDto waitingApproval(String approvalId, String summary) {
        return new ConnectionValidationResponseDto(
                "CONNECTION_PROBE", "WAITING_APPROVAL", approvalId, summary, false, null);
    }

    public static ConnectionValidationResponseDto probeSuccess() {
        return new ConnectionValidationResponseDto("CONNECTION_PROBE", "VERIFIED", null, null, true, null);
    }

    public static ConnectionValidationResponseDto probeFailed(String errors) {
        return new ConnectionValidationResponseDto("CONNECTION_PROBE", "FAILED", null, null, false, errors);
    }
}
