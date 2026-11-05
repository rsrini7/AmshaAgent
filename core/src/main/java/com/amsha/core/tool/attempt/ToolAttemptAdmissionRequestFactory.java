package com.amsha.core.tool.attempt;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.amsha.persistence.api.ToolAttemptAdmissionRequest;
import com.amsha.persistence.api.ToolAttemptIdempotencyMode;
import com.amsha.tool.api.IdempotencyMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Builds persistence admission requests and their approved non-secret metadata. */
final class ToolAttemptAdmissionRequestFactory {
    private ToolAttemptAdmissionRequestFactory() {}

    static ToolAttemptAdmissionRequest request(
            ObjectMapper objectMapper,
            String workspaceId,
            String sessionId,
            String operationId,
            String retryOfAttemptId,
            String toolName,
            String actionFingerprint,
            ToolTargetResolution target,
            ToolAttemptIdempotencyMode mode,
            String executionKind) {
        return new ToolAttemptAdmissionRequest(
                workspaceId,
                sessionId,
                operationId,
                retryOfAttemptId,
                toolName,
                actionFingerprint,
                ToolOperationIdentity.CURRENT_FINGERPRINT_VERSION,
                target.targetFingerprint(),
                mode,
                executionKind,
                safeMetadataJson(objectMapper, operationId, toolName, actionFingerprint, target),
                Instant.now());
    }

    static ToolAttemptIdempotencyMode attemptMode(IdempotencyMode mode) {
        return switch (mode) {
            case IDEMPOTENT -> ToolAttemptIdempotencyMode.IDEMPOTENT;
            case AT_MOST_ONCE -> ToolAttemptIdempotencyMode.AT_MOST_ONCE;
            case NON_IDEMPOTENT -> ToolAttemptIdempotencyMode.NON_IDEMPOTENT;
        };
    }

    private static String safeMetadataJson(
            ObjectMapper objectMapper,
            String operationId,
            String toolName,
            String actionFingerprint,
            ToolTargetResolution target) {
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("actionFingerprint", actionFingerprint);
        metadata.put("lockScope", target.scope().name());
        metadata.put("operationId", operationId);
        metadata.put("targetFingerprint", target.targetFingerprint());
        metadata.put("toolName", toolName);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Safe tool-attempt metadata could not be serialized.", e);
        }
    }
}
