package com.amsha.onboarding.dto;

import java.util.List;

public record CompleteResponse(
        boolean complete,
        String chatUrl,
        String provider,
        String model,
        String serviceKey,
        List<PluginStepDto> pluginSteps) {}
