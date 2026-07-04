package com.amsha.app.config;

import java.nio.file.Path;

/**
 * Normalizes LLM configuration from legacy environment-variable names to the
 * dotted property names used by {@code application.yaml}. All decisions are
 * config-driven; provider/model-specific hardcoded defaults have been removed.
 */
public class LlmConfigAutoFixer {

    private static final String DEFAULT_CONTEXT_WINDOW = "128000";

    private LlmConfigAutoFixer() {
        // Utility class for static helpers only.
    }

    public static void validateAndAutoFixLlmConfig(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        String provider = System.getProperty("AMSHA_LLM_PROVIDER", System.getenv("AMSHA_LLM_PROVIDER"));
        if (provider == null || provider.isBlank()) provider = "groq";

        // Per-provider base URLs and models are configured in each provider's
        // @ConfigProperty annotations with sensible defaults. No auto-fix needed.

        resolveContextWindow();
        resolveStructuredOutputModes();
    }

    private static void resolveContextWindow() {
        String current = System.getProperty("AMSHA_LLM_CONTEXT_WINDOW", System.getenv("AMSHA_LLM_CONTEXT_WINDOW"));
        String effective = (current == null || current.isBlank()) ? DEFAULT_CONTEXT_WINDOW : current;
        System.setProperty("AMSHA_LLM_CONTEXT_WINDOW", effective);
        System.setProperty("amsha.llm.context-window", effective);

        // Respect an explicitly configured compact-prompt flag; do not derive it from
        // context-window size. Default to false when unset so application.yaml defaults apply.
        String compact = System.getProperty(
                "AMSHA_LLM_COMPACT_PROMPT_ENABLED", System.getenv("AMSHA_LLM_COMPACT_PROMPT_ENABLED"));
        if (compact == null || compact.isBlank()) {
            System.setProperty("AMSHA_LLM_COMPACT_PROMPT_ENABLED", "false");
            System.setProperty("amsha.llm.compact-prompt-enabled", "false");
        }
    }

    private static void resolveStructuredOutputModes() {
        // Respect an explicitly configured structured-output mode; do not derive it from
        // the selected model. Default to NATIVE when unset so application.yaml defaults apply.
        String mode = System.getProperty(
                "AMSHA_LLM_STRUCTURED_OUTPUT_MODE", System.getenv("AMSHA_LLM_STRUCTURED_OUTPUT_MODE"));
        if (mode == null || mode.isBlank()) {
            System.setProperty("AMSHA_LLM_STRUCTURED_OUTPUT_MODE", "NATIVE");
            System.setProperty("amsha.llm.structured-output-mode", "NATIVE");
        }
    }
}
