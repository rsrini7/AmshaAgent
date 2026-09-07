package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.onboarding.dto.OnboardingFieldDescriptorDto;
import com.amsha.onboarding.dto.OnboardingStepDescriptorDto;
import com.amsha.onboarding.i18n.OnboardingI18nService;
import com.amsha.onboarding.spi.OnboardingExtension;
import com.amsha.onboarding.spi.OnboardingFieldDescriptor;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.validation.OnboardingStepValidator;

/**
 * Registry and localized descriptor resolver for all onboarding stages (core and extension).
 */
@ApplicationScoped
public class OnboardingStepDescriptorService {

    @Inject
    Instance<OnboardingExtension> extensions;

    @Inject
    OnboardingI18nService i18nService;

    @Inject
    OnboardingStepValidator stepValidator;

    /**
     * Resolves all step descriptors for the effective plan in order, with localized titles and descriptions.
     */
    public List<OnboardingStepDescriptorDto> getEffectiveDescriptors(List<String> effectivePlan, Locale locale) {
        List<OnboardingStepDescriptorDto> dtos = new ArrayList<>();
        for (int i = 0; i < effectivePlan.size(); i++) {
            String stepId = effectivePlan.get(i);
            getDescriptor(stepId, i + 1, locale).ifPresent(dtos::add);
        }
        return List.copyOf(dtos);
    }

    /**
     * Resolves a descriptor for a single step.
     */
    public Optional<OnboardingStepDescriptorDto> getDescriptor(String stepId, int order, Locale locale) {
        // Check if an extension provides a descriptor
        for (OnboardingExtension ext : extensions) {
            OnboardingStepDescriptor d = ext.descriptor();
            if (d.extensionId().equals(stepId)) {
                stepValidator.validateDescriptor(d);
                return Optional.of(resolveExtensionDescriptor(d, order, locale));
            }
        }

        // Otherwise generate for core step
        return Optional.of(resolveCoreDescriptor(stepId, order, locale));
    }

    private OnboardingStepDescriptorDto resolveExtensionDescriptor(
            OnboardingStepDescriptor d, int order, Locale locale) {
        String title = i18nService.find(d.titleKey(), locale).orElse(d.extensionId());
        String desc = i18nService.find(d.descriptionKey(), locale).orElse("");
        List<OnboardingFieldDescriptorDto> fields =
                d.fields().stream().map(f -> resolveFieldDescriptor(f, locale)).toList();
        return new OnboardingStepDescriptorDto(
                d.extensionId(), d.extensionVersion(), order, d.optional(), title, desc, fields);
    }

    private OnboardingFieldDescriptorDto resolveFieldDescriptor(OnboardingFieldDescriptor f, Locale locale) {
        String fieldTitle = i18nService.find(f.titleKey(), locale).orElse(f.name());
        String fieldDesc = i18nService.find(f.descriptionKey(), locale).orElse("");
        return new OnboardingFieldDescriptorDto(
                f.name(),
                f.type().name(),
                f.required(),
                f.options() != null ? f.options() : List.of(),
                fieldTitle,
                fieldDesc);
    }

    private OnboardingStepDescriptorDto resolveCoreDescriptor(String stepId, int order, Locale locale) {
        String titleKey = "onboarding." + stepId + ".title";
        String descKey = "onboarding." + stepId + ".desc";
        String title = i18nService.find(titleKey, locale).orElse(stepId);
        String desc = i18nService.find(descKey, locale).orElse("");
        boolean optional = !"welcome".equals(stepId) && !"connections".equals(stepId) && !"review".equals(stepId);
        return new OnboardingStepDescriptorDto(stepId, "1.0", order, optional, title, desc, List.of());
    }
}
