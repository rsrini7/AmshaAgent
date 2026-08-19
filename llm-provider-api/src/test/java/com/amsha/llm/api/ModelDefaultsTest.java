package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Constructor;

import org.junit.jupiter.api.Test;

class ModelDefaultsTest {

    @Test
    void testPrivateConstructor() throws Exception {
        Constructor<ModelDefaults> constructor = ModelDefaults.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    @Test
    void testDefaultModelForProvider_allProviders() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("groq"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_MODEL, ModelDefaults.defaultModelForProvider("openai"));
        assertEquals(ModelDefaults.DEFAULT_OLLAMA_MODEL, ModelDefaults.defaultModelForProvider("ollama"));
        assertEquals(ModelDefaults.DEFAULT_ANTHROPIC_MODEL, ModelDefaults.defaultModelForProvider("anthropic"));
        assertEquals(ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL, ModelDefaults.defaultModelForProvider("claude-code"));
        assertEquals(ModelDefaults.DEFAULT_OPENROUTER_MODEL, ModelDefaults.defaultModelForProvider("openrouter"));
        assertEquals(ModelDefaults.DEFAULT_NVIDIA_MODEL, ModelDefaults.defaultModelForProvider("nvidia"));
        assertEquals(ModelDefaults.DEFAULT_CEREBRAS_MODEL, ModelDefaults.defaultModelForProvider("cerebras"));
        assertEquals(ModelDefaults.DEFAULT_SARVAM_MODEL, ModelDefaults.defaultModelForProvider("sarvam"));
        assertEquals(ModelDefaults.DEFAULT_OPENCODE_MODEL, ModelDefaults.defaultModelForProvider("opencode"));
        assertEquals(ModelDefaults.DEFAULT_HUGGINGFACE_MODEL, ModelDefaults.defaultModelForProvider("huggingface"));
        assertEquals(ModelDefaults.DEFAULT_MISTRAL_MODEL, ModelDefaults.defaultModelForProvider("mistral"));
        assertEquals(ModelDefaults.DEFAULT_CLOUDFLARE_MODEL, ModelDefaults.defaultModelForProvider("cloudflare"));
        assertEquals(ModelDefaults.DEFAULT_COHERE_MODEL, ModelDefaults.defaultModelForProvider("cohere"));
        assertEquals(ModelDefaults.DEFAULT_CODEX_MODEL, ModelDefaults.defaultModelForProvider("codex"));
        assertEquals(ModelDefaults.DEFAULT_AGY_MODEL, ModelDefaults.defaultModelForProvider("agy"));
        assertEquals(ModelDefaults.DEFAULT_KIMCHI_MODEL, ModelDefaults.defaultModelForProvider("kimchi"));
        assertEquals(ModelDefaults.DEFAULT_FOUNDRY_MODEL, ModelDefaults.defaultModelForProvider("foundry"));
        assertEquals(ModelDefaults.DEFAULT_GEMINI_MODEL, ModelDefaults.defaultModelForProvider("gemini"));
        assertEquals(ModelDefaults.DEFAULT_OMLX_MODEL, ModelDefaults.defaultModelForProvider("omlx"));
        assertEquals(ModelDefaults.DEFAULT_LLAMACPP_MODEL, ModelDefaults.defaultModelForProvider("llamacpp"));
        assertEquals(
                ModelDefaults.DEFAULT_GENERIC_OPENAI_MODEL, ModelDefaults.defaultModelForProvider("generic-openai"));
        assertEquals(ModelDefaults.DEFAULT_INFINITE_LOOP_MODEL, ModelDefaults.defaultModelForProvider("infinite-loop"));
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider(null));
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("unknown"));
    }

    @Test
    void testDefaultBaseUrlForProvider_allProviders() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("groq"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("openai"));
        assertEquals(ModelDefaults.DEFAULT_OLLAMA_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("ollama"));
        assertEquals(ModelDefaults.DEFAULT_ANTHROPIC_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("anthropic"));
        assertEquals(
                ModelDefaults.DEFAULT_CLAUDE_CODE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("claude-code"));
        assertEquals(ModelDefaults.DEFAULT_OPENROUTER_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("openrouter"));
        assertEquals(ModelDefaults.DEFAULT_NVIDIA_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("nvidia"));
        assertEquals(ModelDefaults.DEFAULT_CEREBRAS_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("cerebras"));
        assertEquals(ModelDefaults.DEFAULT_SARVAM_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("sarvam"));
        assertEquals(ModelDefaults.DEFAULT_OPENCODE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("opencode"));
        assertEquals(
                ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("huggingface"));
        assertEquals(ModelDefaults.DEFAULT_MISTRAL_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("mistral"));
        assertEquals(ModelDefaults.DEFAULT_CLOUDFLARE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("cloudflare"));
        assertEquals(ModelDefaults.DEFAULT_COHERE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("cohere"));
        assertEquals(ModelDefaults.DEFAULT_CODEX_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("codex"));
        assertEquals(ModelDefaults.DEFAULT_AGY_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("agy"));
        assertEquals(ModelDefaults.DEFAULT_KIMCHI_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("kimchi"));
        assertEquals(ModelDefaults.DEFAULT_FOUNDRY_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("foundry"));
        assertEquals(ModelDefaults.DEFAULT_GEMINI_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("gemini"));
        assertEquals(ModelDefaults.DEFAULT_OMLX_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("omlx"));
        assertEquals(ModelDefaults.DEFAULT_LLAMACPP_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("llamacpp"));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider(null));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider("unknown"));
    }

    @Test
    void testInferProviderFromUrl() {
        assertEquals(ModelDefaults.PROVIDER_OPENAI, ModelDefaults.inferProviderFromUrl("https://api.openai.com/v1"));
        assertEquals(ModelDefaults.PROVIDER_GROQ, ModelDefaults.inferProviderFromUrl("https://api.groq.com/openai/v1"));
        assertEquals(
                ModelDefaults.PROVIDER_NVIDIA,
                ModelDefaults.inferProviderFromUrl("https://integrate.api.nvidia.com/v1"));
        assertEquals(ModelDefaults.PROVIDER_CEREBRAS, ModelDefaults.inferProviderFromUrl("https://api.cerebras.ai/v1"));
        assertEquals(ModelDefaults.PROVIDER_SARVAM, ModelDefaults.inferProviderFromUrl("https://api.sarvam.ai/v1"));
        assertEquals(ModelDefaults.PROVIDER_OPENCODE, ModelDefaults.inferProviderFromUrl("https://opencode.ai/zen/v1"));
        assertEquals(
                ModelDefaults.PROVIDER_HUGGINGFACE,
                ModelDefaults.inferProviderFromUrl("https://router.huggingface.co/v1"));
        assertEquals(ModelDefaults.PROVIDER_MISTRAL, ModelDefaults.inferProviderFromUrl("https://api.mistral.ai/v1"));
        assertEquals(
                ModelDefaults.PROVIDER_CLOUDFLARE,
                ModelDefaults.inferProviderFromUrl("https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1"));
        assertEquals(
                ModelDefaults.PROVIDER_COHERE,
                ModelDefaults.inferProviderFromUrl("https://api.cohere.ai/compatibility/v1"));
        assertEquals(
                ModelDefaults.PROVIDER_GEMINI,
                ModelDefaults.inferProviderFromUrl("https://generativelanguage.googleapis.com/v1beta"));
        assertEquals(ModelDefaults.PROVIDER_ANTHROPIC, ModelDefaults.inferProviderFromUrl("https://api.anthropic.com"));
        assertEquals(
                ModelDefaults.PROVIDER_KIMCHI, ModelDefaults.inferProviderFromUrl("https://llm.kimchi.dev/openai/v1"));
        assertEquals(ModelDefaults.PROVIDER_OLLAMA, ModelDefaults.inferProviderFromUrl("http://localhost:11434"));
        assertEquals(ModelDefaults.PROVIDER_OLLAMA, ModelDefaults.inferProviderFromUrl("http://127.0.0.1:11434"));
        assertEquals(ModelDefaults.PROVIDER_OMLX, ModelDefaults.inferProviderFromUrl("http://localhost:8000/v1"));
        assertEquals(ModelDefaults.PROVIDER_OMLX, ModelDefaults.inferProviderFromUrl("http://127.0.0.1:8000/v1"));
        assertEquals(ModelDefaults.PROVIDER_LLAMACPP, ModelDefaults.inferProviderFromUrl("http://localhost:9000/v1"));
        assertEquals(ModelDefaults.PROVIDER_LLAMACPP, ModelDefaults.inferProviderFromUrl("http://127.0.0.1:9000/v1"));
        assertEquals(ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.inferProviderFromUrl(null));
        assertEquals(ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.inferProviderFromUrl(""));
        assertEquals(ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.inferProviderFromUrl("   "));
        assertEquals(
                ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.inferProviderFromUrl("https://custom.endpoint.com"));
    }
}
