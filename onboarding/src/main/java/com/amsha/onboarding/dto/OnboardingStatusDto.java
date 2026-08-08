package com.amsha.onboarding.dto;

public record OnboardingStatusDto(
        int step,
        String stepName,
        String provider,
        String model,
        boolean credentialsOk,
        boolean skipOnboarding,
        boolean vaultPriority,
        String effectiveProvider,
        String effectiveModel,
        String effectiveBaseUrl,
        String effectiveSource,
        String credentialState,
        String credentialSource) {}
