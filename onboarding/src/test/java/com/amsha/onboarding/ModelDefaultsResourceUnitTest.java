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
    }

    @Test
    void testGetDefaultForProviderSarvam() {
        Response response = resource.getDefaultForProvider("sarvam");
        assertEquals(200, response.getStatus());
        Map<?, ?> map = (Map<?, ?>) response.getEntity();
        assertEquals("sarvam-105b", map.get("defaultModel"));
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
}
