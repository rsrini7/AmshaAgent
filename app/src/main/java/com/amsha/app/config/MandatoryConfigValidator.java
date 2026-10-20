package com.amsha.app.config;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.ModelDefaults;

/**
 * Validates existence of mandatory API keys and environment variables.
 */
public class MandatoryConfigValidator {

    public static void validateMandatoryConfig() {
        String mainKey = System.getProperty("AMSHA_LLM_API_KEY", System.getenv("AMSHA_LLM_API_KEY"));
        String provider = System.getProperty(ConfigKeys.LLM_PROVIDER, System.getenv(ConfigKeys.LLM_PROVIDER));

        if (!ModelDefaults.PROVIDER_OLLAMA.equalsIgnoreCase(provider)) {
            validateKey("AMSHA_LLM_API_KEY", mainKey, "LLM provider: " + provider);
        }

        validateKey("BRAVE_SEARCH_API_KEY", System.getProperty("BRAVE_SEARCH_API_KEY"), "Web search");
        validateKey("GITHUB_TOKEN", System.getProperty("GITHUB_TOKEN"), "GitHub integration");
    }

    private static void validateKey(String keyName, String value, String context) {
        if (value != null && value.startsWith("changeme-")) {
            System.err.println("[Amsha Agent] ❌ CRITICAL: " + keyName + " has placeholder value for " + context);
            System.exit(1);
        }
    }
}
