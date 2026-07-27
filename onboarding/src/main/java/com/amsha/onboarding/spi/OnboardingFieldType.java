package com.amsha.onboarding.spi;

/**
 * A closed, versioned field descriptor set. Adapters/extensions declare typed safe fields only;
 * they do not supply markup, client validation code, or arbitrary regular expressions.
 */
public enum OnboardingFieldType {
    TEXT,
    SECRET,
    URL,
    SELECT,
    CHECKBOX,
    MODEL_SELECTOR
}
