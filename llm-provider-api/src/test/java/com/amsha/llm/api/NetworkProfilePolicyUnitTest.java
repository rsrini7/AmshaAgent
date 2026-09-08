package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NetworkProfilePolicyUnitTest {

    @Test
    void allowAllFactory() {
        NetworkProfilePolicy p = NetworkProfilePolicy.allowAll();
        assertAll(
                () -> assertEquals(NetworkProfilePolicy.EgressMode.ALLOW_ALL, p.egressMode()),
                () -> assertTrue(p.allowedHosts().isEmpty()),
                () -> assertFalse(p.requireProxy()),
                () -> assertTrue(p.proxyUrl().isEmpty()),
                () -> assertTrue(p.proxyCredentialVaultRef().isEmpty()),
                () -> assertTrue(p.noProxyHosts().isEmpty()),
                () -> assertEquals(0, p.connectTimeoutMs()),
                () -> assertEquals(0, p.readTimeoutMs()),
                () -> assertEquals(NetworkProfilePolicy.TlsTrustMode.SYSTEM, p.tlsTrustMode()),
                () -> assertTrue(p.customCaVaultRef().isEmpty()));
    }

    @Test
    void blankJsonDefaultsToAllowAll() {
        NetworkProfilePolicy p = NetworkProfilePolicy.fromJson("");
        assertEquals(NetworkProfilePolicy.EgressMode.ALLOW_ALL, p.egressMode());
        assertTrue(p.isHostAllowed("https://anything.example.com/v1"));
        assertTrue(NetworkProfilePolicy.fromJson(null).isHostAllowed("https://example.com"));
    }

    @Test
    void unparseableJsonFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> NetworkProfilePolicy.fromJson("{not json"));
    }

    @Test
    void allowListPermitsOnlyListedHost() {
        NetworkProfilePolicy p =
                NetworkProfilePolicy.fromJson("{\"egressMode\":\"ALLOW_LIST\",\"allowedHosts\":[\"api.openai.com\"]}");
        assertEquals(NetworkProfilePolicy.EgressMode.ALLOW_LIST, p.egressMode());
        assertTrue(p.isHostAllowed("https://api.openai.com/v1"));
        assertFalse(p.isHostAllowed("https://api.groq.com/openai/v1"));
        assertFalse(p.isHostAllowed("not an uri"));
    }

    @Test
    void denyAllBlocksEverything() {
        NetworkProfilePolicy p = NetworkProfilePolicy.fromJson("{\"egressMode\":\"DENY_ALL\"}");
        assertEquals(NetworkProfilePolicy.EgressMode.DENY_ALL, p.egressMode());
        assertFalse(p.isHostAllowed("https://api.openai.com/v1"));
    }

    @Test
    void proxyFieldsParsed() {
        NetworkProfilePolicy p = NetworkProfilePolicy.fromJson(
                "{\"egressMode\":\"ALLOW_ALL\",\"requireProxy\":true,\"proxyUrl\":\"http://proxy:3128\",\"proxyCredentialVaultRef\":\"vault-key-1\",\"connectTimeoutMs\":\"5000\",\"readTimeoutMs\":30000,\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"vault-ca-1\"}");
        assertTrue(p.requireProxy());
        assertTrue(p.proxyUrl().isPresent());
        assertEquals("http://proxy:3128", p.proxyUrl().get());
        assertTrue(p.proxyCredentialVaultRef().isPresent());
        assertEquals("vault-key-1", p.proxyCredentialVaultRef().get());
        assertEquals(5000, p.connectTimeoutMs());
        assertEquals(30000, p.readTimeoutMs());
        assertEquals(NetworkProfilePolicy.TlsTrustMode.CUSTOM_CA, p.tlsTrustMode());
        assertEquals("vault-ca-1", p.customCaVaultRef().orElseThrow());
    }

    @Test
    void customCaWithoutRefFallsBackToSystem() {
        NetworkProfilePolicy p =
                NetworkProfilePolicy.fromJson("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"\"}");
        assertEquals(NetworkProfilePolicy.TlsTrustMode.SYSTEM, p.tlsTrustMode());
        assertTrue(p.customCaVaultRef().isEmpty());
    }

    @Test
    void noProxyMatchingWildcard() {
        NetworkProfilePolicy p = NetworkProfilePolicy.fromJson("{\"noProxyHosts\":[\"localhost\",\"*.internal\"]}");
        assertTrue(p.isNoProxyHost("localhost"));
        assertTrue(p.isNoProxyHost("service.internal"));
        assertFalse(p.isNoProxyHost("api.openai.com"));
        assertFalse(p.isNoProxyHost(null));
        assertFalse(NetworkProfilePolicy.allowAll().isNoProxyHost("localhost"));
    }

    @Test
    void isValidJsonTests() {
        assertFalse(NetworkProfilePolicy.isValidJson(null));
        assertFalse(NetworkProfilePolicy.isValidJson(""));
        assertFalse(NetworkProfilePolicy.isValidJson("{invalid"));
        assertTrue(NetworkProfilePolicy.isValidJson("{}"));

        // allow_list requires non-empty allowedHosts
        assertFalse(NetworkProfilePolicy.isValidJson("{\"egressMode\":\"ALLOW_LIST\"}"));
        assertFalse(NetworkProfilePolicy.isValidJson("{\"egressMode\":\"ALLOW_LIST\",\"allowedHosts\":[]}"));
        assertTrue(NetworkProfilePolicy.isValidJson("{\"egressMode\":\"ALLOW_LIST\",\"allowedHosts\":[\"host\"]}"));

        // requireProxy requires valid http/https uri
        assertFalse(NetworkProfilePolicy.isValidJson("{\"requireProxy\":true}"));
        assertFalse(NetworkProfilePolicy.isValidJson("{\"requireProxy\":true,\"proxyUrl\":\"invalid\"}"));
        assertTrue(NetworkProfilePolicy.isValidJson("{\"requireProxy\":true,\"proxyUrl\":\"http://proxy:8080\"}"));

        // CUSTOM_CA requires customCaVaultRef
        assertFalse(NetworkProfilePolicy.isValidJson("{\"tlsTrustMode\":\"CUSTOM_CA\"}"));
        assertFalse(NetworkProfilePolicy.isValidJson("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"\"}"));
        assertTrue(NetworkProfilePolicy.isValidJson("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ref\"}"));

        // timeouts must be positive
        assertFalse(NetworkProfilePolicy.isValidJson("{\"connectTimeoutMs\":-1}"));
        assertFalse(NetworkProfilePolicy.isValidJson("{\"readTimeoutMs\":0}"));
        assertTrue(NetworkProfilePolicy.isValidJson("{\"connectTimeoutMs\":1000,\"readTimeoutMs\":2000}"));
    }
}
