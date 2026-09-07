package com.amsha.onboarding.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.amsha.onboarding.i18n.OnboardingI18nService;
import com.amsha.onboarding.spi.OnboardingFieldDescriptor;
import com.amsha.onboarding.spi.OnboardingFieldType;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;

/**
 * Validates step descriptors against i18n bundles and validates form input against field descriptors.
 *
 * <p>Invariant (§4, §6):
 * The onboarding module provides one Java validator and one small Lit renderer for that contract;
 * adapters do not supply markup, client validation code, or arbitrary regular expressions.
 * Descriptor i18n keys are verified against message bundles on registration.
 */
@ApplicationScoped
public class OnboardingStepValidator {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_SECRET_LENGTH = 2000;

    @Inject
    OnboardingI18nService i18nService;

    public OnboardingStepValidator() {}

    public OnboardingStepValidator(OnboardingI18nService i18nService) {
        this.i18nService = i18nService;
    }

    /**
     * Validates a step descriptor's metadata and ensures all i18n keys exist in English.
     *
     * @throws IllegalArgumentException if the descriptor is malformed or has missing i18n keys
     */
    public void validateDescriptor(OnboardingStepDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Descriptor cannot be null");
        }
        if (!SAFE_IDENTIFIER.matcher(descriptor.extensionId()).matches()) {
            throw new IllegalArgumentException("Invalid extensionId: " + descriptor.extensionId());
        }
        if (descriptor.extensionVersion() == null
                || descriptor.extensionVersion().isBlank()) {
            throw new IllegalArgumentException("extensionVersion is required");
        }
        if (descriptor.titleKey() != null && !descriptor.titleKey().isBlank()) {
            i18nService.requireKey(descriptor.titleKey());
        }
        if (descriptor.descriptionKey() != null && !descriptor.descriptionKey().isBlank()) {
            i18nService.requireKey(descriptor.descriptionKey());
        }

        for (OnboardingFieldDescriptor field : descriptor.fields()) {
            validateFieldDescriptor(field);
        }
    }

    private void validateFieldDescriptor(OnboardingFieldDescriptor field) {
        if (field == null) {
            throw new IllegalArgumentException("Field descriptor cannot be null");
        }
        if (!SAFE_IDENTIFIER.matcher(field.name()).matches()) {
            throw new IllegalArgumentException("Invalid field name: " + field.name());
        }
        if (field.type() == null) {
            throw new IllegalArgumentException("Field type is required for " + field.name());
        }
        if (field.titleKey() != null && !field.titleKey().isBlank()) {
            i18nService.requireKey(field.titleKey());
        }
        if (field.descriptionKey() != null && !field.descriptionKey().isBlank()) {
            i18nService.requireKey(field.descriptionKey());
        }
        if (field.type() == OnboardingFieldType.SELECT) {
            if (field.options() == null || field.options().isEmpty()) {
                throw new IllegalArgumentException("SELECT field " + field.name() + " must define bounded options");
            }
        }
    }

    /**
     * Validates input values against a step descriptor.
     */
    public OnboardingValidationResult validateInput(OnboardingStepDescriptor descriptor, Map<String, Object> input) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> safeInput = input != null ? input : Map.of();

        for (OnboardingFieldDescriptor field : descriptor.fields()) {
            Object rawVal = safeInput.get(field.name());
            String val = rawVal != null ? String.valueOf(rawVal).trim() : "";

            if (field.required() && val.isBlank()) {
                errors.add(field.name() + " is required");
                continue;
            }
            if (val.isBlank()) {
                continue;
            }

            validateFieldValue(field, val, rawVal, errors);
        }

        if (!errors.isEmpty()) {
            return OnboardingValidationResult.fail(String.join("; ", errors));
        }
        return OnboardingValidationResult.ok();
    }

    private void validateFieldValue(OnboardingFieldDescriptor field, String val, Object rawVal, List<String> errors) {
        switch (field.type()) {
            case TEXT -> {
                if (val.length() > MAX_TEXT_LENGTH) {
                    errors.add(field.name() + " exceeds maximum length of " + MAX_TEXT_LENGTH);
                }
            }
            case SECRET -> {
                if (val.length() > MAX_SECRET_LENGTH) {
                    errors.add(field.name() + " exceeds maximum length of " + MAX_SECRET_LENGTH);
                }
            }
            case URL -> {
                try {
                    URI uri = URI.create(val);
                    String scheme = uri.getScheme();
                    if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                        errors.add(field.name() + " must use http or https scheme");
                    } else if (uri.getUserInfo() != null) {
                        errors.add(field.name() + " must not contain credentials in userinfo");
                    }
                } catch (Exception _) {
                    errors.add(field.name() + " is not a valid URL");
                }
            }
            case SELECT -> {
                if (field.options() == null || !field.options().contains(val)) {
                    errors.add(field.name() + " must be one of: " + field.options());
                }
            }
            case CHECKBOX -> {
                if (rawVal instanceof Boolean) {
                    // Valid boolean
                } else if (!"true".equalsIgnoreCase(val) && !"false".equalsIgnoreCase(val)) {
                    errors.add(field.name() + " must be a boolean value");
                }
            }
            case MODEL_SELECTOR -> {
                if (val.length() > MAX_TEXT_LENGTH) {
                    errors.add(field.name() + " exceeds maximum length");
                }
            }
        }
    }
}
