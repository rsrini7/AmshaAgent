package com.arcclaw.core.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standardized REST error response structure.
 * Renamed to ArcClawErrorResponse to avoid naming conflicts (§QW-11).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ArcClawErrorResponse(
    String error,
    String detail,
    String path,
    Instant timestamp
) {
    public ArcClawErrorResponse(String error) {
        this(error, null, null, Instant.now());
    }

    public ArcClawErrorResponse(String error, String detail) {
        this(error, detail, null, Instant.now());
    }
}
