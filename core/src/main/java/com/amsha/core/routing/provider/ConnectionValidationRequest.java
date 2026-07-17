package com.amsha.core.routing.provider;

import java.util.List;

/**
 * Immutable request for tiered provider validation of an onboarding connection selection. Carries
 * only non-secret fields; the secret itself is submitted through a separate scoped secret-input
 * handle and is never included here.
 */
public record ConnectionValidationRequest(
        String providerId,
        String modelId,
        String customBaseUrl,
        boolean apiKeySupplied,
        List<String> allowedEgressHosts) {

    public ConnectionValidationRequest {
        if (providerId == null) providerId = "";
        if (modelId == null) modelId = "";
        if (customBaseUrl == null) customBaseUrl = "";
        if (allowedEgressHosts == null) allowedEgressHosts = List.of();
        else allowedEgressHosts = List.copyOf(allowedEgressHosts);
    }
}
