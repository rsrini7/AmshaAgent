package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConnectionValidationRequestTest {

    @Test
    void validatesRequestCreationAndNormalizesDefaults() {
        ConnectionValidationRequest req1 = new ConnectionValidationRequest(
                "openai", "gpt-4o", "http://localhost", true, List.of("api.openai.com"));
        assertAll(
                () -> assertEquals("openai", req1.providerId()),
                () -> assertEquals("gpt-4o", req1.modelId()),
                () -> assertEquals("http://localhost", req1.customBaseUrl()),
                () -> assertTrue(req1.apiKeySupplied()),
                () -> assertEquals(List.of("api.openai.com"), req1.allowedEgressHosts()));

        ConnectionValidationRequest req2 = new ConnectionValidationRequest(null, null, null, false, null);
        assertAll(
                () -> assertEquals("", req2.providerId()),
                () -> assertEquals("", req2.modelId()),
                () -> assertEquals("", req2.customBaseUrl()),
                () -> assertFalse(req2.apiKeySupplied()),
                () -> assertTrue(req2.allowedEgressHosts().isEmpty()));
    }
}
