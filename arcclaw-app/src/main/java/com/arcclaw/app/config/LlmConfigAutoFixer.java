package com.arcclaw.app.config;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Handles LLM model and base URL auto-correction based on the selected provider.
 */
public class LlmConfigAutoFixer {

    public static void validateAndAutoFixLlmConfig(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        String provider = System.getProperty("ARCCLAW_LLM_PROVIDER", System.getenv("ARCCLAW_LLM_PROVIDER"));
        if (provider == null || provider.isBlank()) provider = "groq";

        // Per-provider base URLs and models are configured in each provider's
        // @ConfigProperty annotations with sensible defaults. No auto-fix needed.

        resolveContextWindow(envFile, provider);
        resolveStructuredOutputModes(provider);
    }

    private static void resolveContextWindow(Path envFile, String provider) {
        String current = System.getProperty("ARCCLAW_LLM_CONTEXT_WINDOW", System.getenv("ARCCLAW_LLM_CONTEXT_WINDOW"));
        String target =
                switch (provider.toLowerCase(Locale.ROOT)) {
                    case "gemini" -> "1000000";
                    case "ollama" -> "8192";
                    default -> "128000";
                };
        String effective = (current == null || current.isBlank()) ? target : current;
        System.setProperty("ARCCLAW_LLM_CONTEXT_WINDOW", effective);
        System.setProperty("arcclaw.llm.context-window", effective);

        boolean compact = Integer.parseInt(effective) <= 8192;
        System.setProperty("ARCCLAW_LLM_COMPACT_PROMPT_ENABLED", String.valueOf(compact));
        System.setProperty("arcclaw.llm.compact-prompt-enabled", String.valueOf(compact));
    }

    private static void resolveStructuredOutputModes(String provider) {
        String modelKey = "ARCCLAW_" + provider.toUpperCase(Locale.ROOT) + "_MODEL";
        String model = System.getProperty(modelKey, System.getenv(modelKey));
        String target = (model != null && model.contains("llama-4-scout")) ? "PROMPT_DIRECTIVE" : "NATIVE";
        System.setProperty("arcclaw.llm.structured-output-mode", target);
    }
}
