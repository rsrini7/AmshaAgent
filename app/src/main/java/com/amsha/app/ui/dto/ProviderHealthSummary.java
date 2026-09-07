package com.amsha.app.ui.dto;

public record ProviderHealthSummary(
        String providerName, boolean isDefault, String status, String baseUrl, String details) {}
