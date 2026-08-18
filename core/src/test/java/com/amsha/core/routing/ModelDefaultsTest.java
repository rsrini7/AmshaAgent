package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelDefaultsTest {

    @Test
    void testDefaultModelForProvider_knownProviders() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("groq"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_MODEL, ModelDefaults.defaultModelForProvider("openai"));
        assertEquals(ModelDefaults.DEFAULT_OLLAMA_MODEL, ModelDefaults.defaultModelForProvider("ollama"));
        assertEquals(ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL, ModelDefaults.defaultModelForProvider("claude-code"));
        assertEquals(ModelDefaults.DEFAULT_GEMINI_MODEL, ModelDefaults.defaultModelForProvider("gemini"));
        assertEquals(ModelDefaults.DEFAULT_SARVAM_MODEL, ModelDefaults.defaultModelForProvider("sarvam"));
        assertEquals(ModelDefaults.DEFAULT_OPENCODE_MODEL, ModelDefaults.defaultModelForProvider("opencode"));
        assertEquals(ModelDefaults.DEFAULT_HUGGINGFACE_MODEL, ModelDefaults.defaultModelForProvider("huggingface"));
        assertEquals(ModelDefaults.DEFAULT_MISTRAL_MODEL, ModelDefaults.defaultModelForProvider("mistral"));
        assertEquals(ModelDefaults.DEFAULT_CLOUDFLARE_MODEL, ModelDefaults.defaultModelForProvider("cloudflare"));
        assertEquals(ModelDefaults.DEFAULT_COHERE_MODEL, ModelDefaults.defaultModelForProvider("cohere"));
        assertEquals(ModelDefaults.DEFAULT_CODEX_MODEL, ModelDefaults.defaultModelForProvider("codex"));
    }

    @Test
    void testDefaultModelForProvider_caseInsensitiveAndNull() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("GROQ"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_MODEL, ModelDefaults.defaultModelForProvider("OpenAI"));
        assertEquals(ModelDefaults.DEFAULT_SARVAM_MODEL, ModelDefaults.defaultModelForProvider("SARVAM"));
        assertEquals(ModelDefaults.DEFAULT_OPENCODE_MODEL, ModelDefaults.defaultModelForProvider("OPENCODE"));
        assertEquals(ModelDefaults.DEFAULT_HUGGINGFACE_MODEL, ModelDefaults.defaultModelForProvider("HUGGINGFACE"));
        assertEquals(ModelDefaults.DEFAULT_MISTRAL_MODEL, ModelDefaults.defaultModelForProvider("MISTRAL"));
        assertEquals(ModelDefaults.DEFAULT_CLOUDFLARE_MODEL, ModelDefaults.defaultModelForProvider("CLOUDFLARE"));
        assertEquals(ModelDefaults.DEFAULT_COHERE_MODEL, ModelDefaults.defaultModelForProvider("COHERE"));
        assertEquals(ModelDefaults.DEFAULT_CODEX_MODEL, ModelDefaults.defaultModelForProvider("CODEX"));
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider(null));
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("unknown-provider"));
    }

    @Test
    void testDefaultBaseUrlForProvider_knownProviders() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("groq"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("openai"));
        assertEquals(ModelDefaults.DEFAULT_OLLAMA_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("ollama"));
        assertEquals(ModelDefaults.DEFAULT_SARVAM_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("sarvam"));
        assertEquals(ModelDefaults.DEFAULT_OPENCODE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("opencode"));
        assertEquals(
                ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("huggingface"));
        assertEquals(ModelDefaults.DEFAULT_MISTRAL_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("mistral"));
        assertEquals(ModelDefaults.DEFAULT_CLOUDFLARE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("cloudflare"));
        assertEquals(ModelDefaults.DEFAULT_COHERE_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("cohere"));
        assertEquals(ModelDefaults.DEFAULT_CODEX_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("codex"));
        assertEquals("https://api.openai.com/v1", ModelDefaults.DEFAULT_CODEX_OPENAI_BASE_URL);
        assertEquals("http://127.0.0.1:8787/v1", ModelDefaults.DEFAULT_CODEX_HROOM_BASE_URL);
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider("claude-code"));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider(null));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider("unknown"));
    }

    @Test
    void testInferProviderFromUrl() {
        assertEquals(ModelDefaults.PROVIDER_OPENAI, ModelDefaults.inferProviderFromUrl("https://api.openai.com/v1"));
        assertEquals(ModelDefaults.PROVIDER_GROQ, ModelDefaults.inferProviderFromUrl("https://api.groq.com/openai/v1"));
        assertEquals(ModelDefaults.PROVIDER_SARVAM, ModelDefaults.inferProviderFromUrl("https://api.sarvam.ai/v1"));
        assertEquals(ModelDefaults.PROVIDER_OPENCODE, ModelDefaults.inferProviderFromUrl("https://opencode.ai/zen/v1"));
        assertEquals(
                ModelDefaults.PROVIDER_OPENCODE, ModelDefaults.inferProviderFromUrl("https://opencode.ai/zen/go/v1"));
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
        assertEquals(ModelDefaults.PROVIDER_OLLAMA, ModelDefaults.inferProviderFromUrl("http://localhost:11434"));
        assertEquals(ModelDefaults.PROVIDER_LLAMACPP, ModelDefaults.inferProviderFromUrl("http://localhost:9000/v1"));
        assertEquals(ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.inferProviderFromUrl(null));
        assertEquals(
                ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.inferProviderFromUrl("https://custom.endpoint.com"));
    }

    @Test
    void testInferProviderFromUrl_recognizesCentralizedDefaults() {
        assertEquals(
                ModelDefaults.PROVIDER_ANTHROPIC,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_ANTHROPIC_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_SARVAM,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_SARVAM_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_OPENCODE,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_OPENCODE_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_HUGGINGFACE,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_MISTRAL,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_MISTRAL_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_CLOUDFLARE,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_CLOUDFLARE_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_COHERE,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_COHERE_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_OMLX, ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_OMLX_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_LLAMACPP,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_LLAMACPP_BASE_URL));
    }
}
