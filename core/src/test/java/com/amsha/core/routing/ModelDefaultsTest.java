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
    }

    @Test
    void testDefaultModelForProvider_caseInsensitiveAndNull() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("GROQ"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_MODEL, ModelDefaults.defaultModelForProvider("OpenAI"));
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider(null));
        assertEquals(ModelDefaults.DEFAULT_GROQ_MODEL, ModelDefaults.defaultModelForProvider("unknown-provider"));
    }

    @Test
    void testDefaultBaseUrlForProvider_knownProviders() {
        assertEquals(ModelDefaults.DEFAULT_GROQ_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("groq"));
        assertEquals(ModelDefaults.DEFAULT_OPENAI_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("openai"));
        assertEquals(ModelDefaults.DEFAULT_OLLAMA_BASE_URL, ModelDefaults.defaultBaseUrlForProvider("ollama"));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider("claude-code"));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider(null));
        assertEquals("", ModelDefaults.defaultBaseUrlForProvider("unknown"));
    }

    @Test
    void testInferProviderFromUrl() {
        assertEquals(ModelDefaults.PROVIDER_OPENAI, ModelDefaults.inferProviderFromUrl("https://api.openai.com/v1"));
        assertEquals(ModelDefaults.PROVIDER_GROQ, ModelDefaults.inferProviderFromUrl("https://api.groq.com/openai/v1"));
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
                ModelDefaults.PROVIDER_OMLX, ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_OMLX_BASE_URL));
        assertEquals(
                ModelDefaults.PROVIDER_LLAMACPP,
                ModelDefaults.inferProviderFromUrl(ModelDefaults.DEFAULT_LLAMACPP_BASE_URL));
    }
}
