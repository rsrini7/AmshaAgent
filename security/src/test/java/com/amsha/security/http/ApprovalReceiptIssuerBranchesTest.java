package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import com.amsha.security.api.OutboundPurpose;

import org.junit.jupiter.api.Test;

/**
 * Branch coverage for single-use outbound approval receipts (relocated in S7): context
 * validation, consumption semantics, expiry, and purpose/host binding. The issuer's CDI
 * dependencies are optional by design; these tests exercise the no-store paths directly.
 */
class ApprovalReceiptIssuerBranchesTest {

    private final ApprovalReceiptIssuer issuer = new ApprovalReceiptIssuer();

    @Test
    void explicitApprovalRejectsIncompleteContext() {
        assertThrows(
                IllegalArgumentException.class,
                () -> issuer.issueForExplicitApproval(null, "op-1", OutboundPurpose.MCP_CONNECT, "api.example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> issuer.issueForExplicitApproval("user-1", " ", OutboundPurpose.MCP_CONNECT, "api.example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> issuer.issueForExplicitApproval("user-1", "op-1", null, "api.example.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> issuer.issueForExplicitApproval("user-1", "op-1", OutboundPurpose.MCP_CONNECT, " "));
    }

    @Test
    void issuedReceiptVerifiesAndConsumesExactlyOnce() {
        String token =
                issuer.issueForExplicitApproval("user-1", "op-1", OutboundPurpose.MCP_CONNECT, "api.example.com");

        var first = issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "api.example.com");
        assertNotNull(first);
        assertEquals("user-1", first.approvedByUserId());
        assertEquals(OutboundPurpose.MCP_CONNECT, first.purpose());
        assertEquals("api.example.com", first.targetHost());

        // Single use: the second presentation must be rejected.
        assertNull(issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "api.example.com"));
    }

    @Test
    void verifyRejectsNullOrUnknownTokens() {
        assertNull(issuer.verifyAndConsume(null, OutboundPurpose.PROVIDER_API_CALL, "host"));
        assertNull(issuer.verifyAndConsume("", OutboundPurpose.PROVIDER_API_CALL, "host"));
        assertNull(issuer.verifyAndConsume("not-a-real-token", OutboundPurpose.PROVIDER_API_CALL, "host"));
    }

    @Test
    void verifyEnforcesPurposeAndHostBinding() {
        String token =
                issuer.issueForExplicitApproval("user-2", "op-2", OutboundPurpose.MCP_CONNECT, "api.example.com");

        assertNull(
                issuer.verifyAndConsume(token, OutboundPurpose.PROVIDER_API_CALL, "api.example.com"),
                "purpose mismatch must reject");
        assertNull(
                issuer.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "other.example.com"),
                "host mismatch must reject");
    }

    @Test
    void expiredReceiptsAreRejectedAndPurgeable() throws Exception {
        var shortLived = new ApprovalReceiptIssuer(Duration.ofMillis(1));
        String token =
                shortLived.issueForExplicitApproval("user-3", "op-3", OutboundPurpose.MCP_CONNECT, "api.example.com");

        Thread.sleep(5);
        assertNull(shortLived.verifyAndConsume(token, OutboundPurpose.MCP_CONNECT, "api.example.com"));

        // purgeExpired must not throw on an empty/expired map.
        assertDoesNotThrow(() -> {
            shortLived.purgeExpired();
            issuer.purgeExpired();
        });
    }
}
