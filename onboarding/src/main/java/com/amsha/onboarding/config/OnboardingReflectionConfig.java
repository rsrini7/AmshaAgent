package com.amsha.onboarding.config;

import com.amsha.core.security.PreparedBootstrapCredential;
import com.amsha.onboarding.OnboardingCommitSaga;
import com.amsha.onboarding.OnboardingCoordinator;
import com.amsha.onboarding.OnboardingProfileExporter;
import com.amsha.onboarding.OnboardingResource;
import com.amsha.onboarding.OnboardingSelectionScrubber;
import com.amsha.onboarding.dto.OnboardingStatusDto;
import com.amsha.onboarding.dto.PromptUpdateRequest;
import com.amsha.onboarding.dto.ProviderUpdateRequest;
import com.amsha.onboarding.dto.SettingsDto;
import com.amsha.onboarding.extension.McpOnboardingExtension;
import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingFieldDescriptor;
import com.amsha.onboarding.spi.OnboardingFieldType;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;
import com.amsha.onboarding.spi.ValidatedOnboardingInput;
import com.amsha.persistence.api.OnboardingCommitReceiptDto;
import com.amsha.persistence.api.OnboardingDraftPurpose;
import com.amsha.persistence.api.OnboardingDraftState;
import com.amsha.persistence.api.OnboardingExtensionStateDto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Dedicated configuration class to register all Onboarding module DTOs for GraalVM Native Image
 * reflection. The draft service is the single source of truth; the legacy numbered-step DTOs have
 * been removed.
 */
@RegisterForReflection(
        targets = {
            OnboardingStatusDto.class,
            ProviderUpdateRequest.class,
            PromptUpdateRequest.class,
            SettingsDto.class,
            OnboardingCoordinator.class,
            OnboardingCommitSaga.class,
            OnboardingProfileExporter.class,
            OnboardingSelectionScrubber.class,
            OnboardingResource.class,
            OnboardingContext.class,
            OnboardingExtension.class,
            OnboardingFieldDescriptor.class,
            OnboardingFieldType.class,
            OnboardingInput.class,
            OnboardingStepDescriptor.class,
            OnboardingValidationResult.class,
            PreparedOnboardingStep.class,
            ValidatedOnboardingInput.class,
            PreparedBootstrapCredential.class,
            OnboardingResource.StepUpdateRequest.class,
            McpOnboardingExtension.class,
            OnboardingCommitReceiptDto.class,
            OnboardingDraftPurpose.class,
            OnboardingDraftState.class,
            OnboardingExtensionStateDto.class
        })
public class OnboardingReflectionConfig {
    // Container for @RegisterForReflection
}
