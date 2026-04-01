package com.arcclaw.onboarding.dto;

public record OnboardingStatusDto(
    int step, String stepName, String provider,
    String model, boolean credentialsOk) {}
