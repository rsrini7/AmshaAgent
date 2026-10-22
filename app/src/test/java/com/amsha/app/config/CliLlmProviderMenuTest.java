package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.amsha.llm.api.ModelDefaults;

import org.junit.jupiter.api.Test;

class CliLlmProviderMenuTest {

    @Test
    void mapsEveryMenuChoiceThroughTheSingleProviderRegistry() {
        assertEquals(ModelDefaults.PROVIDER_GROQ, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_GROQ));
        assertEquals(
                ModelDefaults.PROVIDER_OPENROUTER,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_OPENROUTER));
        assertEquals(
                ModelDefaults.PROVIDER_OPENAI, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_OPENAI));
        assertEquals(
                ModelDefaults.PROVIDER_OLLAMA, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_OLLAMA));
        assertEquals(
                ModelDefaults.PROVIDER_NVIDIA, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_NVIDIA));
        assertEquals(
                ModelDefaults.PROVIDER_GEMINI, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_GEMINI));
        assertEquals(
                ModelDefaults.PROVIDER_CEREBRAS,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_CEREBRAS));
        assertEquals(ModelDefaults.PROVIDER_OMLX, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_OMLX));
        assertEquals(
                ModelDefaults.PROVIDER_GENERIC_OPENAI,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_GENERIC_OPENAI));
        assertEquals(
                ModelDefaults.PROVIDER_SARVAM, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_SARVAM));
        assertEquals(
                ModelDefaults.PROVIDER_OPENCODE,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_OPENCODE));
        assertEquals(
                ModelDefaults.PROVIDER_HUGGINGFACE,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_HUGGINGFACE));
        assertEquals(
                ModelDefaults.PROVIDER_MISTRAL,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_MISTRAL));
        assertEquals(
                ModelDefaults.PROVIDER_CLOUDFLARE,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_CLOUDFLARE));
        assertEquals(
                ModelDefaults.PROVIDER_COHERE, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_COHERE));
        assertEquals(
                ModelDefaults.PROVIDER_CODEX, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_CODEX));
        assertEquals(
                ModelDefaults.PROVIDER_CLAUDE_CODE,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_CLAUDE_CODE));
        assertEquals(ModelDefaults.PROVIDER_AGY, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_AGY));
        assertEquals(
                ModelDefaults.PROVIDER_KIMCHI, CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_KIMCHI));
        assertEquals(
                ModelDefaults.PROVIDER_FOUNDRY,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_FOUNDRY));
        assertEquals(
                ModelDefaults.PROVIDER_LLAMACPP,
                CliLlmProviderMenu.providerForChoice(CliLlmProviderMenu.CHOICE_LLAMACPP));
    }

    @Test
    void defaultsUnknownChoicesToGroq() {
        assertEquals(ModelDefaults.PROVIDER_GROQ, CliLlmProviderMenu.providerForChoice(null));
        assertEquals(ModelDefaults.PROVIDER_GROQ, CliLlmProviderMenu.providerForChoice("unknown"));
    }
}
