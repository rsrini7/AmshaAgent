package com.amsha.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboundHttpPolicyServiceTest {

    @Mock
    AuditService auditService;

    OutboundHttpPolicyService service;

    @BeforeEach
    void setUp() {
        service = new OutboundHttpPolicyService();
        service.auditService = auditService;
    }

    // ---- SSRF protection ----

    @Test
    void blocksPrivateIp_10x() {
        // HTTPS required for non-local; TLS check fires before SSRF for plain HTTP.
        OutboundPolicyDecision d =
                service.evaluate("http://10.0.0.1/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
        assertEquals("TLS_REQUIRED", d.reason().orElseThrow());
    }

    @Test
    void blocksPrivateIp_10x_https() {
        // With HTTPS, SSRF check fires.
        OutboundPolicyDecision d =
                service.evaluate("https://10.0.0.1/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
        assertEquals("BLOCKED_LITERAL_ADDRESS", d.reason().orElseThrow());
    }

    @Test
    void blocksPrivateIp_192168x() {
        OutboundPolicyDecision d =
                service.evaluate("https://192.168.1.1/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
    }

    @Test
    void blocksPrivateIp_17216x() {
        OutboundPolicyDecision d =
                service.evaluate("https://172.16.0.1/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
    }

    @Test
    void blocksMetadataEndpoint() {
        OutboundPolicyDecision d = service.evaluate(
                "https://169.254.169.254/latest/meta-data/", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
        assertEquals("BLOCKED_LITERAL_ADDRESS", d.reason().orElseThrow());
    }

    @Test
    void blocksLoopback() {
        OutboundPolicyDecision d =
                service.evaluate("http://127.0.0.1/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        // Localhost HTTP is allowed for development (Ollama, local MCP).
        assertTrue(d.isAllowed());
    }

    @Test
    void blocksIpv6Loopback() {
        OutboundPolicyDecision d =
                service.evaluate("http://[::1]/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isAllowed());
    }

    // ---- TLS enforcement ----

    @Test
    void blocksNonTlsForNonLocal() {
        OutboundPolicyDecision d =
                service.evaluate("http://api.openai.com/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
        assertEquals("TLS_REQUIRED", d.reason().orElseThrow());
    }

    @Test
    void allowsHttpsForNonLocal() {
        OutboundPolicyDecision d =
                service.evaluate("https://api.openai.com/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isAllowed());
    }

    @Test
    void allowsHttpForLocalhost() {
        OutboundPolicyDecision d =
                service.evaluate("http://localhost:11434/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isAllowed());
    }

    @Test
    void allowsHttpFor127_0_0_1() {
        OutboundPolicyDecision d =
                service.evaluate("http://127.0.0.1:8080/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isAllowed());
    }

    // ---- HITL gating ----

    @Test
    void catalogProbe_requiresHitl() {
        OutboundPolicyDecision d =
                service.evaluate("https://api.openai.com/v1/models", OutboundPurpose.CATALOG_PROBE, null, "user-1");
        assertTrue(d.requiresHitl());
    }

    @Test
    void oauthCallback_requiresHitl() {
        // Use a resolvable domain so DNS succeeds and HITL is reached.
        OutboundPolicyDecision d = service.evaluate(
                "https://api.openai.com/v1/oauth/callback", OutboundPurpose.OAUTH_CALLBACK, null, "user-1");
        assertTrue(d.requiresHitl());
    }

    @Test
    void mcpConnect_requiresHitl() {
        // Use a resolvable domain so DNS succeeds and HITL is reached.
        OutboundPolicyDecision d =
                service.evaluate("https://api.openai.com/v1/mcp/sse", OutboundPurpose.MCP_CONNECT, null, "user-1");
        assertTrue(d.requiresHitl());
    }

    @Test
    void providerApiCall_doesNotRequireHitl() {
        OutboundPolicyDecision d = service.evaluate(
                "https://api.openai.com/v1/chat/completions", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isAllowed());
    }

    @Test
    void mcpToolCall_doesNotRequireHitl() {
        // Use a resolvable domain so DNS succeeds; MCP_TOOL_CALL does not require HITL.
        OutboundPolicyDecision d = service.evaluate(
                "https://api.openai.com/v1/mcp/tools/invoke", OutboundPurpose.MCP_TOOL_CALL, null, "user-1");
        assertTrue(d.isAllowed());
    }

    // ---- URL validation ----

    @Test
    void rejectsInvalidUrl() {
        OutboundPolicyDecision d = service.evaluate("not-a-url", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
    }

    @Test
    void rejectsUrlWithoutHost() {
        OutboundPolicyDecision d = service.evaluate("https:///path", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
    }

    // ---- DNS rebinding protection ----

    @Test
    void blocksIpv4MappedIpv6Loopback() {
        OutboundPolicyDecision d =
                service.evaluate("https://[::ffff:127.0.0.1]/v1", OutboundPurpose.PROVIDER_API_CALL, null, "user-1");
        assertTrue(d.isDenied());
        assertEquals("BLOCKED_LITERAL_ADDRESS", d.reason().orElseThrow());
    }

    // ---- Decision structure ----

    @Test
    void allowDecision_hasNoReason() {
        OutboundPolicyDecision d = OutboundPolicyDecision.allow(OutboundPurpose.PROVIDER_API_CALL, "user-1");
        assertTrue(d.isAllowed());
        assertFalse(d.reason().isPresent());
        assertEquals(OutboundPurpose.PROVIDER_API_CALL, d.purpose());
        assertEquals("user-1", d.actorUserId());
    }

    @Test
    void denyDecision_hasReason() {
        OutboundPolicyDecision d = OutboundPolicyDecision.deny("test reason", OutboundPurpose.CATALOG_PROBE, "user-1");
        assertTrue(d.isDenied());
        assertEquals("test reason", d.reason().orElse(""));
    }

    @Test
    void hitlDecision_hasReason() {
        OutboundPolicyDecision d =
                OutboundPolicyDecision.requireHitl("needs approval", OutboundPurpose.MCP_CONNECT, "user-1");
        assertTrue(d.requiresHitl());
        assertEquals("needs approval", d.reason().orElse(""));
    }
}
