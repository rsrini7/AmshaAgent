package com.amsha.core.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standardized REST error response structure.
 * Renamed to AmshaErrorResponse to avoid naming conflicts (§QW-11).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AmshaErrorResponse(String code, String error, String detail, String path, Instant timestamp) {
    public AmshaErrorResponse(String error) {
        this(null, error, null, null, Instant.now());
    }

    public AmshaErrorResponse(String error, String detail) {
        this(null, error, detail, null, Instant.now());
    }

    public AmshaErrorResponse(String code, String error, String detail) {
        this(code, error, detail, null, Instant.now());
    }
}
