package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.core.Response;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelDefaultsResourceUnitTest {

    private final ModelDefaultsResource resource = new ModelDefaultsResource();

    @Test
    void testGetAllDefaults() {
        Response response = resource.getAllDefaults();
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertTrue(map.containsKey("groq"));
        assertTrue(map.containsKey("openai"));
        assertTrue(map.containsKey("sarvam"));
        assertTrue(map.containsKey("opencode"));
        assertTrue(map.containsKey("huggingface"));
        assertTrue(map.containsKey("mistral"));
        assertTrue(map.containsKey("cloudflare"));
        assertTrue(map.containsKey("cohere"));
        assertTrue(map.containsKey("codex"));
    }

    @Test
    void testGetDefaultForProviderCodex() {
        Response response = resource.getDefaultForProvider("codex");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("gpt-5.6-luna", map.get("defaultModel"));
        assertEquals("https://chatgpt.com/backend-api/codex", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderSarvam() {
        Response response = resource.getDefaultForProvider("sarvam");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("sarvam-105b-conversations", map.get("defaultModel"));
        assertEquals("https://api.sarvam.ai/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderOpenCode() {
        Response response = resource.getDefaultForProvider("opencode");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("deepseek-v4-flash-free", map.get("defaultModel"));
        assertEquals("https://opencode.ai/zen/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderHuggingFace() {
        Response response = resource.getDefaultForProvider("huggingface");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("meta-llama/Llama-3.3-70B-Instruct", map.get("defaultModel"));
        assertEquals("https://router.huggingface.co/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderMistral() {
        Response response = resource.getDefaultForProvider("mistral");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("codestral-latest", map.get("defaultModel"));
        assertEquals("https://api.mistral.ai/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderCloudflare() {
        Response response = resource.getDefaultForProvider("cloudflare");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("@cf/meta/llama-3.3-70b-instruct-fp8-fast", map.get("defaultModel"));
        assertEquals("https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderCohere() {
        Response response = resource.getDefaultForProvider("cohere");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("command-r-08-2024", map.get("defaultModel"));
        assertEquals("https://api.cohere.ai/compatibility/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderKimchi() {
        Response response = resource.getDefaultForProvider("kimchi");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("minimax-m3", map.get("defaultModel"));
        assertEquals("https://llm.kimchi.dev/openai/v1", map.get("defaultBaseUrl"));
    }

    @Test
    void testGetDefaultForProviderFoundry() {
        Response response = resource.getDefaultForProvider("foundry");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("qwen3-14b", map.get("defaultModel"));
        assertEquals("http://127.0.0.1:8000/v1", map.get("defaultBaseUrl"));
    }
}
