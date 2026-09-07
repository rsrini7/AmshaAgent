package com.amsha.onboarding.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.onboarding.i18n.OnboardingI18nService;
import com.amsha.onboarding.spi.OnboardingFieldDescriptor;
import com.amsha.onboarding.spi.OnboardingFieldType;
import com.amsha.onboarding.spi.OnboardingStepDescriptor;
import com.amsha.onboarding.spi.OnboardingValidationResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingStepValidatorTest {

    private OnboardingI18nService i18nService;
    private OnboardingStepValidator validator;

    @BeforeEach
    void setUp() {
        i18nService = new OnboardingI18nService();
        validator = new OnboardingStepValidator(i18nService);
    }

    @Test
    void validatesValidDescriptorWithExistingI18nKeys() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "custom-step",
                "1.0",
                50,
                true,
                List.of(new OnboardingFieldDescriptor(
                        "serverName",
                        OnboardingFieldType.TEXT,
                        true,
                        List.of(),
                        "onboarding.mcp.name.title",
                        "onboarding.mcp.name.desc")),
                "onboarding.welcome.title",
                "onboarding.welcome.desc");

        validator.validateDescriptor(desc);
    }

    @Test
    void rejectsDescriptorWithMissingEnglishI18nKey() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "custom-step", "1.0", 50, true, List.of(), "missing.title.key", "onboarding.welcome.desc");

        assertThrows(IllegalArgumentException.class, () -> validator.validateDescriptor(desc));
    }

    @Test
    void rejectsFieldDescriptorWithMissingEnglishI18nKey() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "custom-step",
                "1.0",
                50,
                true,
                List.of(new OnboardingFieldDescriptor(
                        "serverName",
                        OnboardingFieldType.TEXT,
                        true,
                        List.of(),
                        "non.existent.field.title",
                        "onboarding.mcp.name.desc")),
                "onboarding.welcome.title",
                "onboarding.welcome.desc");

        assertThrows(IllegalArgumentException.class, () -> validator.validateDescriptor(desc));
    }

    @Test
    void rejectsSelectFieldWithoutOptions() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "custom-step",
                "1.0",
                50,
                true,
                List.of(new OnboardingFieldDescriptor(
                        "category",
                        OnboardingFieldType.SELECT,
                        true,
                        List.of(),
                        "onboarding.mcp.category.title",
                        "onboarding.mcp.category.desc")),
                "onboarding.welcome.title",
                "onboarding.welcome.desc");

        assertThrows(IllegalArgumentException.class, () -> validator.validateDescriptor(desc));
    }

    @Test
    void validatesInputFieldsSuccessfully() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "mcp-step",
                "1.0",
                50,
                true,
                List.of(
                        new OnboardingFieldDescriptor("name", OnboardingFieldType.TEXT, true, List.of(), null, null),
                        new OnboardingFieldDescriptor("endpoint", OnboardingFieldType.URL, true, List.of(), null, null),
                        new OnboardingFieldDescriptor(
                                "category",
                                OnboardingFieldType.SELECT,
                                true,
                                List.of("INTERNAL", "EXTERNAL"),
                                null,
                                null),
                        new OnboardingFieldDescriptor(
                                "active", OnboardingFieldType.CHECKBOX, false, List.of(), null, null)),
                null,
                null);

        Map<String, Object> input = Map.of(
                "name", "brave-search",
                "endpoint", "https://api.brave.com/v1",
                "category", "INTERNAL",
                "active", true);

        OnboardingValidationResult res = validator.validateInput(desc, input);
        assertTrue(res.valid());
    }

    @Test
    void rejectsInputWithMissingRequiredField() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "mcp-step",
                "1.0",
                50,
                true,
                List.of(new OnboardingFieldDescriptor("name", OnboardingFieldType.TEXT, true, List.of(), null, null)),
                null,
                null);

        OnboardingValidationResult res = validator.validateInput(desc, Map.of());
        assertFalse(res.valid());
        assertTrue(res.safeFieldErrors().contains("name is required"));
    }

    @Test
    void rejectsInputWithInvalidUrlOrUserinfo() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "mcp-step",
                "1.0",
                50,
                true,
                List.of(new OnboardingFieldDescriptor(
                        "endpoint", OnboardingFieldType.URL, true, List.of(), null, null)),
                null,
                null);

        OnboardingValidationResult res =
                validator.validateInput(desc, Map.of("endpoint", "http://user:pass@example.com"));
        assertFalse(res.valid());
        assertTrue(res.safeFieldErrors().contains("must not contain credentials in userinfo"));
    }

    @Test
    void rejectsInputWithInvalidSelectOption() {
        OnboardingStepDescriptor desc = new OnboardingStepDescriptor(
                "mcp-step",
                "1.0",
                50,
                true,
                List.of(new OnboardingFieldDescriptor(
                        "category", OnboardingFieldType.SELECT, true, List.of("A", "B"), null, null)),
                null,
                null);

        OnboardingValidationResult res = validator.validateInput(desc, Map.of("category", "INVALID_VAL"));
        assertFalse(res.valid());
        assertTrue(res.safeFieldErrors().contains("must be one of"));
    }
}
