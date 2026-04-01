package com.arcclaw.onboarding.dto;

import java.util.List;

public record CompleteResponse(
    boolean complete, String chatUrl,
    String provider, String model,
    List<PluginStepDto> pluginSteps) {}
