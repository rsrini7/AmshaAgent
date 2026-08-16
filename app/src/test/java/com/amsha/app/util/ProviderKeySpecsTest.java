package com.amsha.app.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProviderKeySpecsTest {

    @Test
    void testLlmKeySpecFor() {
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("groq"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("openrouter"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("openai"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("gemini"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("nvidia"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("cerebras"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("sarvam"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("opencode"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("huggingface"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("mistral"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("omlx"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor("generic-openai"));
        assertNull(ProviderKeySpecs.llmKeySpecFor("ollama"));
        assertNotNull(ProviderKeySpecs.llmKeySpecFor(null));
    }

    @Test
    void testCompactionKeySpecFor() {
        assertNull(ProviderKeySpecs.compactionKeySpecFor("ollama"));
        assertNull(ProviderKeySpecs.compactionKeySpecFor("omlx"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("groq"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("openrouter"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("openai"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("gemini"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("cerebras"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("nvidia"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("sarvam"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("opencode"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("huggingface"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("mistral"));
        assertNotNull(ProviderKeySpecs.compactionKeySpecFor("generic-openai"));
    }

    @Test
    void testClassifierKeySpecFor() {
        assertNull(ProviderKeySpecs.classifierKeySpecFor("ollama"));
        assertNull(ProviderKeySpecs.classifierKeySpecFor("omlx"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("groq"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("openrouter"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("openai"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("gemini"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("cerebras"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("nvidia"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("sarvam"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("opencode"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("huggingface"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("mistral"));
        assertNotNull(ProviderKeySpecs.classifierKeySpecFor("generic-openai"));
    }

    @Test
    void testEmbeddingKeySpecFor() {
        assertNull(ProviderKeySpecs.embeddingKeySpecFor("ollama"));
        assertNotNull(ProviderKeySpecs.embeddingKeySpecFor("openai"));
        assertNotNull(ProviderKeySpecs.embeddingKeySpecFor("jina"));
        assertNull(ProviderKeySpecs.embeddingKeySpecFor("unknown"));
    }

    @Test
    void testFirstProvider() {
        assertEquals("groq", ProviderKeySpecs.firstProvider(null));
        assertEquals("groq", ProviderKeySpecs.firstProvider(""));
        assertEquals("openai", ProviderKeySpecs.firstProvider("openai,ollama"));
        assertEquals("gemini", ProviderKeySpecs.firstProvider("gemini"));
    }
}
