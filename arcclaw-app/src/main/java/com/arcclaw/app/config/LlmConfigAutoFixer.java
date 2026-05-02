package com.arcclaw.app.config;

import java.nio.file.Path;

/**
 * Handles LLM model and base URL auto-correction based on the selected provider.
 */
public class LlmConfigAutoFixer {

    public static void validateAndAutoFixLlmConfig(Path binaryDir) {
        Path envFile = binaryDir.resolve(".env");
        String provider = System.getProperty("ARCCLAW_LLM_PROVIDER", System.getenv("ARCCLAW_LLM_PROVIDER"));
        if (provider == null || provider.isBlank()) provider = "groq";

        String baseUrl = System.getProperty("ARCCLAW_LLM_BASE_URL", System.getenv("ARCCLAW_LLM_BASE_URL"));
        if (baseUrl == null || baseUrl.isBlank()) {
            String defaultUrl = switch (provider.toLowerCase()) {
                case "openrouter" -> "https://openrouter.ai/api/v1";
                case "openai"     -> "https://api.openai.com/v1";
                case "ollama"     -> "http://localhost:11434";
                case "nvidia"     -> "https://integrate.api.nvidia.com/v1";
                default           -> null;
            };
            if (defaultUrl != null && !provider.equals("gemini")) {
                EnvConfigInitializer.patchEnvFile(envFile, "ARCCLAW_LLM_BASE_URL", defaultUrl, EnvConfigInitializer.PatchMode.UPDATE);
                System.setProperty("ARCCLAW_LLM_BASE_URL", defaultUrl);
            }
        }

        autoFixProviderInvariants(envFile, provider);
        resolveContextWindow(envFile, provider);
        resolveStructuredOutputModes();
    }

    private static void autoFixProviderInvariants(Path envFile, String provider) {
        if ("openrouter".equalsIgnoreCase(provider)) {
            fixIfMatches(envFile, "ARCCLAW_LLM_MODEL_NAME", "meta-llama/llama-4-scout-17b-16e-instruct", "openai/gpt-oss-20b:free");
            fixIfMatches(envFile, "ARCCLAW_LLM_BASE_URL", "https://api.groq.com/openai/v1", "https://openrouter.ai/api/v1");
        } else if ("groq".equalsIgnoreCase(provider)) {
            fixIfMatches(envFile, "ARCCLAW_LLM_MODEL_NAME", "openai/gpt-oss-20b:free", "meta-llama/llama-4-scout-17b-16e-instruct");
            fixIfMatches(envFile, "ARCCLAW_LLM_BASE_URL", "https://openrouter.ai/api/v1", "https://api.groq.com/openai/v1");
        }
    }

    private static void resolveContextWindow(Path envFile, String provider) {
        String current = System.getProperty("ARCCLAW_LLM_CONTEXT_WINDOW", System.getenv("ARCCLAW_LLM_CONTEXT_WINDOW"));
        String target = switch (provider.toLowerCase()) {
            case "gemini" -> "1000000";
            case "ollama" -> "8192";
            default       -> "128000";
        };
        String effective = (current == null || current.isBlank()) ? target : current;
        System.setProperty("ARCCLAW_LLM_CONTEXT_WINDOW", effective);
        System.setProperty("arcclaw.llm.context-window", effective);
        
        boolean compact = Integer.parseInt(effective) <= 8192;
        System.setProperty("ARCCLAW_LLM_COMPACT_PROMPT_ENABLED", String.valueOf(compact));
        System.setProperty("arcclaw.llm.compact-prompt-enabled", String.valueOf(compact));
    }

    private static void resolveStructuredOutputModes() {
        String model = System.getProperty("ARCCLAW_LLM_MODEL_NAME");
        String target = (model != null && model.contains("llama-4-scout")) ? "PROMPT_DIRECTIVE" : "NATIVE";
        System.setProperty("arcclaw.llm.structured-output-mode", target);
    }

    private static void fixIfMatches(Path envFile, String key, String wrong, String target) {
        String current = System.getProperty(key, System.getenv(key));
        if (current == null || current.isBlank() || current.equals(wrong)) {
            EnvConfigInitializer.patchEnvFile(envFile, key, target, EnvConfigInitializer.PatchMode.UPDATE);
            System.setProperty(key, target);
        }
    }
}
