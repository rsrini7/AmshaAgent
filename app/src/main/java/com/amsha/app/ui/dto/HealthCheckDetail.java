package com.amsha.app.ui.dto;

import java.util.Map;

public record HealthCheckDetail(String name, String status, Map<String, Object> data) {}
