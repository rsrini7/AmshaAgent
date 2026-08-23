package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import com.amsha.security.api.OutboundPurpose;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApprovalReceiptIssuer}: single-use semantics, host/purpose binding, expiry,
 * and consumption.
 */
class ApprovalReceiptIssuerTest {

    @Test
    void issue_thenVerify_returnsBoundReceipt() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        String token = issuer.issue("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "mcp.example.com");
        assertNotNull(token);

        OutboundApprovalReceipt receipt =
                issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "mcp.example.com");
        assertNotNull(receipt);
        assertEquals("user-1", receipt.approvedByUserId());
        assertEquals("sess-1", receipt.sessionId());
        assertEquals(OutboundPurpose.MCP_CONNECT, receipt.purpose());
        assertEquals("mcp.example.com", receipt.targetHost());
    }

    @Test
    void token_isSingleUse() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        String token = issuer.issue("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "mcp.example.com");

        assertNotNull(issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "mcp.example.com"));
        // Second consumption fails: the token was already consumed.
        assertNull(issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "mcp.example.com"));
    }

    @Test
    void verify_rejectsMismatchedHost() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        String token = issuer.issue("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "mcp.example.com");

        assertNull(issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "evil.example.com"));
    }

    @Test
    void verify_rejectsMismatchedPurpose() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        String token = issuer.issue("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "mcp.example.com");

        assertNull(issuer.verifyAndConsume(token, OutboundPurpose.OAUTH_CALLBACK, "mcp.example.com"));
    }

    @Test
    void verify_rejectsExpiredToken() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer(Duration.ofMillis(-1));
        String token = issuer.issue("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "mcp.example.com");

        assertNull(issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "mcp.example.com"));
    }

    @Test
    void verify_rejectsNullToken() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();
        assertNull(issuer.verifyAndConsume(null, OutboundPurpose.MCP_CONNECT, "mcp.example.com"));
    }

    @Test
    void purgeExpired_removesStaleTokens() {
        ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer(Duration.ofMillis(-1));
        String deadToken = issuer.issue("user-1", "sess-1", OutboundPurpose.MCP_CONNECT, "mcp.example.com");
        issuer.purgeExpired();
        // Expired tokens must not be consumable after purge (and were never valid).
        assertNull(issuer.verifyAndConsume(deadToken, OutboundPurpose.MCP_CONNECT, "mcp.example.com"));

        // A freshly-issued token on a healthy issuer still verifies.
        ApprovalReceiptIssuer liveIssuer = new ApprovalReceiptIssuer();
        String token = liveIssuer.issue("user-2", "sess-2", OutboundPurpose.PROVIDER_API_CALL, "api.example.com");
        assertNotNull(liveIssuer.verifyAndConsume(token, OutboundPurpose.PROVIDER_API_CALL, "api.example.com"));
    }
}
