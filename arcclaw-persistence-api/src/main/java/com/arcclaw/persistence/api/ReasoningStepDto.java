package com.arcclaw.persistence.api;

/**
 * Transfer object for a single reasoning step.
 * Isolates core रीजनिंग logic from persistence implementation.
 */
public record ReasoningStepDto(
    String thoughtJson,
    String action,
    String actionInputJson,
    String observation,
    Integer inputTokens,
    Integer outputTokens,
    Boolean success,
    String errorCode,
    String traceId,
    String rootCauseClass
) {}
