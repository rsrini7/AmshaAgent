package com.amsha.core.routing.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import com.amsha.llm.api.NetworkPolicyViolation;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.persistence.api.LlmConnectionDto;

import org.junit.jupiter.api.Test;

class NetworkProfilePolicyTest {

    @Test
    void blankJsonDefaultsToAllowAll() {
        NetworkProfilePolicy p = NetworkProfilePolicy.fromJson("");
        assertEquals(NetworkProfilePolicy.EgressMode.ALLOW_ALL, p.egressMode());
        assertTrue(p.isHostAllowed("https://anything.example.com/v1"));
    }

    @Test
    void unparseableJsonDefaultsToAllowAll() {
        NetworkProfilePolicy p = NetworkProfilePolicy.fromJson("{not json");
        assertEquals(NetworkProfilePolicy.EgressMode.ALLOW_ALL, p.egressMode());
    }

    @Test
    void allowListPermitsOnlyListedHost() {
        NetworkProfilePolicy p =
                NetworkProfilePolicy.fromJson("{\"egressMode\":\"ALLOW_LIST\",\"allowedHosts\":[\"api.openai.com\"]}");
        assertEquals(NetworkProfilePolicy.EgressMode.ALLOW_LIST, p.egressMode());
        assertTrue(p.isHostAllowed("https://api.openai.com/v1"));
        assertFalse(p.isHostAllowed("https://api.groq.com/openai/v1"));
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
                "{\"egressMode\":\"ALLOW_ALL\",\"requireProxy\":true,\"proxyUrl\":\"http://proxy:3128\"}");
        assertTrue(p.requireProxy());
        assertTrue(p.proxyUrl().isPresent());
        assertEquals("http://proxy:3128", p.proxyUrl().get());
    }

    @Test
    void enforcerThrowsOnDeniedHost() {
        NetworkPolicyEnforcer enforcer = new NetworkPolicyEnforcer();
        LlmConnectionDto conn = new LlmConnectionDto(
                "c-1",
                "USER",
                "u-1",
                "owner",
                "Conn",
                "openai",
                "default",
                null,
                "API_KEY",
                "{\"egressMode\":\"ALLOW_LIST\",\"allowedHosts\":[\"api.openai.com\"]}",
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        // Allowed host passes.
        enforcer.assertEgressAllowed(conn, "https://api.openai.com/v1");
        // Denied host throws a safe violation (no secret material).
        NetworkPolicyViolation ex = assertThrows(
                NetworkPolicyViolation.class, () -> enforcer.assertEgressAllowed(conn, "https://evil.example.com/v1"));
        assertTrue(ex.getMessage().contains("evil.example.com"));
    }

    @Test
    void enforcerAllowsWhenNoPolicy() {
        NetworkPolicyEnforcer enforcer = new NetworkPolicyEnforcer();
        LlmConnectionDto conn = new LlmConnectionDto(
                "c-2",
                "USER",
                "u-2",
                "owner",
                "Conn",
                "openai",
                "default",
                null,
                "API_KEY",
                null,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());
        enforcer.assertEgressAllowed(conn, "https://anything.example.com/v1");
        enforcer.assertEgressAllowed(conn, "http://[::1]:11434/v1");
    }

    @Test
    void enforcerRefusesProxyRequiredUntilClientTransportIsConfigured() {
        NetworkPolicyEnforcer enforcer = new NetworkPolicyEnforcer();
        LlmConnectionDto conn = new LlmConnectionDto(
                "c-proxy",
                "USER",
                "u-1",
                "owner",
                "Conn",
                "openai",
                "default",
                null,
                "API_KEY",
                "{\"requireProxy\":true,\"proxyUrl\":\"http://proxy.internal:3128\"}",
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());

        assertThrows(
                NetworkPolicyViolation.class, () -> enforcer.assertEgressAllowed(conn, "https://api.openai.com/v1"));
    }
}
