package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.amsha.onboarding.spi.OnboardingContext;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingInput;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;
import com.amsha.onboarding.spi.PreparedOnboardingStep;

/** Discovers extension steps and delegates their preparation and validation. */
@ApplicationScoped
public class OnboardingExtensionSteps {

    @Inject
    Instance<OnboardingExtension> extensions;

    public List<String> effectivePlan(List<String> basePlan) {
        List<String> merged = new ArrayList<>(basePlan);
        for (OnboardingExtension extension : extensions) {
            OnboardingStepDescriptor descriptor = extension.descriptor();
            if (descriptor.optional() && !merged.contains(descriptor.extensionId())) {
                int insertIndex = Math.min(descriptor.order() / 10, merged.size() - 1);
                if (insertIndex < 0) {
                    insertIndex = 0;
                }
                merged.add(insertIndex, descriptor.extensionId());
            }
        }
        return List.copyOf(merged);
    }

    public Optional<PreparedOnboardingStep> prepare(String stepId, String ownerId, String sessionId) {
        return extensionForStep(stepId)
                .map(extension -> extension.prepare(new OnboardingContext(ownerId, sessionId, stepId)));
    }

    public Optional<OnboardingValidationResult> validate(
            String stepId, String ownerId, String sessionId, String jsonPayload) {
        return extensionForStep(stepId).map(extension -> {
            OnboardingContext context = new OnboardingContext(ownerId, sessionId, stepId);
            PreparedOnboardingStep prepared = extension.prepare(context);
            return extension.validate(context, prepared, new OnboardingInput(stepId, jsonPayload));
        });
    }

    private Optional<OnboardingExtension> extensionForStep(String stepId) {
        for (OnboardingExtension extension : extensions) {
            if (extension.descriptor().extensionId().equals(stepId)) {
                return Optional.of(extension);
            }
        }
        return Optional.empty();
    }
}
