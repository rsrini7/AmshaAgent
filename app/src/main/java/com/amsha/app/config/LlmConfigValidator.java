package com.amsha.app.config;

import java.nio.file.Path;

/**
 * Validates LLM configuration and warns about missing values.
 * Does NOT modify System properties or .env — user must fix manually.
 * Defaults are provided by {@code application.yaml} {@code ${ENV_VAR:default}} expressions.
 */
public class LlmConfigValidator {

    private LlmConfigValidator() {
        // Utility class for static helpers only.
    }

    public static void validateLlmConfig(Path binaryDir) {
        warnIfMissing("AMSHA_LLM_CONTEXT_WINDOW", "128000");
        warnIfMissing("AMSHA_LLM_COMPACT_PROMPT_ENABLED", "false");
        warnIfMissing("AMSHA_LLM_STRUCTURED_OUTPUT_MODE", "NATIVE");
    }

    private static void warnIfMissing(String envKey, String defaultValue) {
        String value = System.getProperty(envKey, System.getenv(envKey));
        if (value == null || value.isBlank()) {
            System.err.println("[Amsha Agent] ℹ️  " + envKey + " not set, using default: " + defaultValue);
        }
    }
}
