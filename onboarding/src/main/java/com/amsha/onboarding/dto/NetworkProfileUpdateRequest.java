package com.amsha.onboarding.dto;

/**
 * Request body for {@code PATCH /settings/connection-network-profile}. Persists a per-connection
 * network profile (TLS trust mode, timeouts, proxy, no-proxy hosts) into the owner's active draft
 * connections selection. {@code connectionIndex} selects which connection entry to update; {@code -1}
 * targets the first connection.
 *
 * <p>Supports both structured {@link NetworkProfilePolicyDto} via {@code profile} and raw JSON
 * string via {@code networkProfileJson}.</p>
 */
public record NetworkProfileUpdateRequest(
        int connectionIndex, String networkProfileJson, NetworkProfilePolicyDto profile) {

    public NetworkProfileUpdateRequest(int connectionIndex, String networkProfileJson) {
        this(connectionIndex, networkProfileJson, null);
    }
}
