package com.arcclaw.app.config;

/**
 * Validates existence of mandatory API keys and environment variables.
 */
public class MandatoryConfigValidator {

    public static void validateMandatoryConfig() {
        String mainKey = System.getProperty("ARCCLAW_LLM_API_KEY", System.getenv("ARCCLAW_LLM_API_KEY"));
        String provider = System.getProperty("ARCCLAW_LLM_PROVIDER", System.getenv("ARCCLAW_LLM_PROVIDER"));

        if (!"ollama".equalsIgnoreCase(provider)) {
            validateKey("ARCCLAW_LLM_API_KEY", mainKey, "LLM provider: " + provider);
        }

        validateKey("BRAVE_SEARCH_API_KEY", System.getProperty("BRAVE_SEARCH_API_KEY"), "Web search");
        validateKey("GITHUB_TOKEN", System.getProperty("GITHUB_TOKEN"), "GitHub integration");
    }

    private static void validateKey(String keyName, String value, String context) {
        if (value != null && value.startsWith("changeme-")) {
            System.err.println("[ArcClaw] ❌ CRITICAL: " + keyName + " has placeholder value for " + context);
            System.exit(1);
        }
    }
}
