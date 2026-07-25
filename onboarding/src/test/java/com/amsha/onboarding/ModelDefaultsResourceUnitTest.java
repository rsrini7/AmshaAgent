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
    }
}
