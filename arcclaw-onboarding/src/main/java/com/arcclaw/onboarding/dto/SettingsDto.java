package com.arcclaw.onboarding.dto;

public record SettingsDto(
    String provider,
    String model,
    String systemPrompt,
    boolean hasApiKey,
    String appVersion
) {}
