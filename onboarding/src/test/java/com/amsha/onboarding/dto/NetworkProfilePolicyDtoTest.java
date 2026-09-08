package com.amsha.onboarding.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.llm.api.NetworkProfilePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkProfilePolicyDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testDefaultPolicy() {
        NetworkProfilePolicyDto dto = NetworkProfilePolicyDto.defaultPolicy();
        assertEquals("ALLOW_ALL", dto.egressMode());
        assertEquals("SYSTEM", dto.tlsTrustMode());
        assertFalse(dto.requireProxy());
        assertNull(dto.validate());

        String json = dto.toJson(mapper);
        assertTrue(NetworkProfilePolicy.isValidJson(json));
    }

    @Test
    void testValidCustomCaPolicy() {
        NetworkProfilePolicyDto dto = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), 5000, 30000, "CUSTOM_CA", "vault-ca-ref-123");
        assertNull(dto.validate());
        String json = dto.toJson(mapper);
        assertTrue(NetworkProfilePolicy.isValidJson(json));

        NetworkProfilePolicyDto roundTrip = NetworkProfilePolicyDto.fromJson(json);
        assertEquals("CUSTOM_CA", roundTrip.tlsTrustMode());
        assertEquals("vault-ca-ref-123", roundTrip.customCaVaultRef());
        assertEquals(5000, roundTrip.connectTimeoutMs());
        assertEquals(30000, roundTrip.readTimeoutMs());
    }

    @Test
    void testInvalidCustomCaMissingVaultRef() {
        NetworkProfilePolicyDto dto = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), 5000, 30000, "CUSTOM_CA", "");
        String err = dto.validate();
        assertNotNull(err);
        assertTrue(err.contains("customCaVaultRef is required"));
    }

    @Test
    void testAllowListRequiresHosts() {
        NetworkProfilePolicyDto emptyHosts = new NetworkProfilePolicyDto(
                "ALLOW_LIST", List.of(), false, null, null, List.of(), null, null, "SYSTEM", null);
        assertNotNull(emptyHosts.validate());
        assertTrue(emptyHosts.validate().contains("allowedHosts cannot be empty"));

        NetworkProfilePolicyDto withHosts = new NetworkProfilePolicyDto(
                "ALLOW_LIST", List.of("api.openai.com"), false, null, null, List.of(), null, null, "SYSTEM", null);
        assertNull(withHosts.validate());
        assertTrue(NetworkProfilePolicy.isValidJson(withHosts.toJson(mapper)));
    }

    @Test
    void testRequireProxyValidatesUrl() {
        NetworkProfilePolicyDto missingUrl = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), true, null, null, List.of(), null, null, "SYSTEM", null);
        assertNotNull(missingUrl.validate());
        assertTrue(missingUrl.validate().contains("proxyUrl is required"));

        NetworkProfilePolicyDto invalidUrl = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), true, "not-a-url", null, List.of(), null, null, "SYSTEM", null);
        assertNotNull(invalidUrl.validate());

        NetworkProfilePolicyDto validUrl = new NetworkProfilePolicyDto(
                "ALLOW_ALL",
                List.of(),
                true,
                "http://proxy.corp:3128",
                "vault-cred-ref",
                List.of("*.corp"),
                null,
                null,
                "SYSTEM",
                null);
        assertNull(validUrl.validate());
        assertTrue(NetworkProfilePolicy.isValidJson(validUrl.toJson(mapper)));
    }

    @Test
    void testTimeoutBounds() {
        NetworkProfilePolicyDto negativeConnect = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), -1, null, "SYSTEM", null);
        assertNotNull(negativeConnect.validate());
        assertTrue(negativeConnect.validate().contains("connectTimeoutMs must be between 0 and 300000"));

        NetworkProfilePolicyDto excessRead = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), null, 300001, "SYSTEM", null);
        assertNotNull(excessRead.validate());
        assertTrue(excessRead.validate().contains("readTimeoutMs must be between 0 and 300000"));
    }

    @Test
    void testInvalidModes() {
        NetworkProfilePolicyDto badEgress = new NetworkProfilePolicyDto(
                "UNKNOWN_MODE", List.of(), false, null, null, List.of(), null, null, "SYSTEM", null);
        assertNotNull(badEgress.validate());
        assertTrue(badEgress.validate().contains("Invalid egressMode"));

        NetworkProfilePolicyDto badTls = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), false, null, null, List.of(), null, null, "NONE", null);
        assertNotNull(badTls.validate());
        assertTrue(badTls.validate().contains("Invalid tlsTrustMode"));
    }

    @Test
    void testProxyUrlRejectsSsrfLiteralHost() {
        NetworkProfilePolicyDto loopback = new NetworkProfilePolicyDto(
                "ALLOW_ALL", List.of(), true, "http://127.0.0.1:8080", null, List.of(), null, null, "SYSTEM", null);
        assertNotNull(loopback.validate());
        assertTrue(loopback.validate().contains("loopback, link-local, or private IP literal"));

        NetworkProfilePolicyDto linkLocal = new NetworkProfilePolicyDto(
                "ALLOW_ALL",
                List.of(),
                true,
                "http://169.254.169.254:3128",
                null,
                List.of(),
                null,
                null,
                "SYSTEM",
                null);
        assertNotNull(linkLocal.validate());
        assertTrue(linkLocal.validate().contains("loopback, link-local, or private IP literal"));
    }

    @Test
    void testFromJsonThrowsOnMalformedJson() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> NetworkProfilePolicyDto.fromJson("not-json"));
    }
}
