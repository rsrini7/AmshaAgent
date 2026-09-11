package com.amsha.onboarding.i18n;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;

import org.jboss.logging.Logger;

/**
 * Resolves localized text for onboarding steps, fields, and options.
 *
 * <p>Invariant (§4, §6):
 * Descriptor i18n keys are resolved from the onboarding module's message bundles.
 * A missing locale entry falls back to English; if the English key is also missing,
 * validation fails rather than rendering a raw i18n key string.
 */
@ApplicationScoped
public class OnboardingI18nService {

    private static final Logger log = Logger.getLogger(OnboardingI18nService.class);
    private static final String BUNDLE_BASE = "i18n/onboarding";

    /**
     * Resolves a key for the given locale with automatic English fallback.
     *
     * @param key the message key
     * @param locale the target locale, or null for English
     * @return the resolved string, or empty if absent in both target and English bundle
     */
    public Optional<String> find(String key, Locale locale) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        Locale target = locale != null ? locale : Locale.ENGLISH;
        try {
            ResourceBundle bundle =
                    ResourceBundle.getBundle(BUNDLE_BASE, target, getClass().getClassLoader());
            if (bundle.containsKey(key)) {
                return Optional.of(bundle.getString(key));
            }
        } catch (MissingResourceException _) {
            // Bundle not found for specific locale; proceed to English fallback
        }

        // Fallback to English if target was not English
        if (!Locale.ENGLISH.equals(target)) {
            try {
                ResourceBundle enBundle = ResourceBundle.getBundle(
                        BUNDLE_BASE, Locale.ENGLISH, getClass().getClassLoader());
                if (enBundle.containsKey(key)) {
                    return Optional.of(enBundle.getString(key));
                }
            } catch (MissingResourceException e) {
                log.warnf("English i18n bundle missing: %s", e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a key using the English bundle.
     */
    public Optional<String> find(String key) {
        return find(key, Locale.ENGLISH);
    }

    /**
     * Resolves a key or returns null if not found.
     */
    public String resolve(String key, Locale locale) {
        return find(key, locale).orElse(null);
    }

    /**
     * Resolves a key in English or returns null if not found.
     */
    public String resolve(String key) {
        return find(key, Locale.ENGLISH).orElse(null);
    }

    /**
     * Asserts that a key is present in the English bundle.
     *
     * @throws IllegalArgumentException if the key does not exist
     */
    public void requireKey(String key) {
        if (find(key, Locale.ENGLISH).isEmpty()) {
            throw new IllegalArgumentException("Required i18n key is missing in English bundle: " + key);
        }
    }

    /**
     * Returns true if the key is present in the English bundle.
     */
    public boolean hasKey(String key) {
        return find(key, Locale.ENGLISH).isPresent();
    }
}
