package com.amsha.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code PATCH /settings/connection-network-profile}. Persists a per-connection
 * {@code networkProfileJson} (TLS trust mode, timeouts, proxy, no-proxy hosts) into the owner's
 * active draft connections selection. {@code connectionIndex} selects which connection entry to
 * update; {@code -1} targets the first connection.
 */
public record NetworkProfileUpdateRequest(
        int connectionIndex, @NotBlank String networkProfileJson) {}
