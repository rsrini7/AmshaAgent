package com.amsha.app.ui.dto;

import java.util.List;

public record SmallRyeHealthSummary(
        String status, int totalChecks, int passingChecks, List<HealthCheckDetail> checks) {}
