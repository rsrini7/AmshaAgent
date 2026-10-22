package com.amsha.app.config;

import java.util.Map;

import com.amsha.llm.api.ModelDefaults;

/** Holds the additional public provider choices offered by the CLI setup menu. */
final class CliLlmProviderMenu {
    static final String CHOICE_GROQ = "1";
    static final String CHOICE_OPENROUTER = "2";
    static final String CHOICE_OPENAI = "3";
    static final String CHOICE_OLLAMA = "4";
    static final String CHOICE_NVIDIA = "5";
    static final String CHOICE_GEMINI = "6";
    static final String CHOICE_CEREBRAS = "7";
    static final String CHOICE_OMLX = "8";
    static final String CHOICE_GENERIC_OPENAI = "9";
    static final String CHOICE_SARVAM = "10";
    static final String CHOICE_OPENCODE = "11";
    static final String CHOICE_HUGGINGFACE = "12";
    static final String CHOICE_MISTRAL = "13";
    static final String CHOICE_CLOUDFLARE = "14";
    static final String CHOICE_COHERE = "15";
    static final String CHOICE_CODEX = "16";
    static final String CHOICE_CLAUDE_CODE = "17";
    static final String CHOICE_AGY = "18";
    static final String CHOICE_KIMCHI = "19";
    static final String CHOICE_FOUNDRY = "20";
    static final String CHOICE_LLAMACPP = "21";

    private static final Map<String, String> PROVIDER_BY_CHOICE = Map.ofEntries(
            Map.entry(CHOICE_GROQ, ModelDefaults.PROVIDER_GROQ),
            Map.entry(CHOICE_OPENROUTER, ModelDefaults.PROVIDER_OPENROUTER),
            Map.entry(CHOICE_OPENAI, ModelDefaults.PROVIDER_OPENAI),
            Map.entry(CHOICE_OLLAMA, ModelDefaults.PROVIDER_OLLAMA),
            Map.entry(CHOICE_NVIDIA, ModelDefaults.PROVIDER_NVIDIA),
            Map.entry(CHOICE_GEMINI, ModelDefaults.PROVIDER_GEMINI),
            Map.entry(CHOICE_CEREBRAS, ModelDefaults.PROVIDER_CEREBRAS),
            Map.entry(CHOICE_OMLX, ModelDefaults.PROVIDER_OMLX),
            Map.entry(CHOICE_GENERIC_OPENAI, ModelDefaults.PROVIDER_GENERIC_OPENAI),
            Map.entry(CHOICE_SARVAM, ModelDefaults.PROVIDER_SARVAM),
            Map.entry(CHOICE_OPENCODE, ModelDefaults.PROVIDER_OPENCODE),
            Map.entry(CHOICE_HUGGINGFACE, ModelDefaults.PROVIDER_HUGGINGFACE),
            Map.entry(CHOICE_MISTRAL, ModelDefaults.PROVIDER_MISTRAL),
            Map.entry(CHOICE_CLOUDFLARE, ModelDefaults.PROVIDER_CLOUDFLARE),
            Map.entry(CHOICE_COHERE, ModelDefaults.PROVIDER_COHERE),
            Map.entry(CHOICE_CODEX, ModelDefaults.PROVIDER_CODEX),
            Map.entry(CHOICE_CLAUDE_CODE, ModelDefaults.PROVIDER_CLAUDE_CODE),
            Map.entry(CHOICE_AGY, ModelDefaults.PROVIDER_AGY),
            Map.entry(CHOICE_KIMCHI, ModelDefaults.PROVIDER_KIMCHI),
            Map.entry(CHOICE_FOUNDRY, ModelDefaults.PROVIDER_FOUNDRY),
            Map.entry(CHOICE_LLAMACPP, ModelDefaults.PROVIDER_LLAMACPP));

    private CliLlmProviderMenu() {}

    static void printAdditionalProviders() {
        System.err.println("  │  17. Claude Code - Claude CLI OAuth");
        System.err.println("  │  18. Antigravity CLI - Gemini CLI OAuth");
        System.err.println("  │  19. Kimchi - Kimchi CLI OAuth");
        System.err.println("  │  20. Foundry Local - local ONNX inference");
        System.err.println("  │  21. llama.cpp - local GGUF server");
    }

    static String providerForChoice(String choice) {
        String normalizedChoice = choice == null ? "" : choice.trim();
        return PROVIDER_BY_CHOICE.getOrDefault(normalizedChoice, ModelDefaults.PROVIDER_GROQ);
    }
}
