package com.amsha.app.config;

import com.amsha.llm.api.ModelDefaults;

/** Holds the additional public provider choices offered by the CLI setup menu. */
final class CliLlmProviderMenu {
    private CliLlmProviderMenu() {}

    static void printAdditionalProviders() {
        System.err.println("  │  17. Claude Code - Claude CLI OAuth");
        System.err.println("  │  18. Antigravity CLI - Gemini CLI OAuth");
        System.err.println("  │  19. Kimchi - Kimchi CLI OAuth");
        System.err.println("  │  20. Foundry Local - local ONNX inference");
        System.err.println("  │  21. llama.cpp - local GGUF server");
    }

    static String providerForAdditionalChoice(String choice) {
        return switch (choice != null ? choice.trim() : "") {
            case "17" -> ModelDefaults.PROVIDER_CLAUDE_CODE;
            case "18" -> ModelDefaults.PROVIDER_AGY;
            case "19" -> ModelDefaults.PROVIDER_KIMCHI;
            case "20" -> ModelDefaults.PROVIDER_FOUNDRY;
            case "21" -> ModelDefaults.PROVIDER_LLAMACPP;
            default -> ModelDefaults.PROVIDER_GROQ;
        };
    }
}
