package com.amsha.app.ui.dto;

import java.util.List;

public record CockpitHealthResponse(
        String status,
        SmallRyeHealthSummary healthChecks,
        DbConnectionPoolSummary database,
        List<ProviderHealthSummary> providers,
        long timestamp) {}
