package com.amsha.onboarding.spi;

/**
 * SPI for contributing additional wizard steps beyond the 5 core steps.
 * All @ApplicationScoped implementations are discovered via CDI Instance<>.
 * TelegramOnboardingProvider and BraveOnboardingProvider implement this
 * in future phases. No implementations exist in Phase 83A.
 */
public interface OnboardingStepProvider {
    String stepId(); // e.g. "telegram", "brave"

    int order(); // display order after core step 5

    String title(); // human-readable for progress bar

    boolean isConfigured(); // true if already fully set up
}
