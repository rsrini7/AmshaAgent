package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundHttpExceptionTest {

    @Test
    @DisplayName("Factory methods create OutboundHttpException with correct categories")
    void factoryMethods_correctCategories() {
        OutboundHttpException policy = OutboundHttpException.policy("denied");
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, policy.category());
        assertEquals("denied", policy.getMessage());

        OutboundHttpException hitl = OutboundHttpException.hitl("approval needed");
        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, hitl.category());
        assertEquals("approval needed", hitl.getMessage());

        OutboundHttpException dnsRebinding = OutboundHttpException.dnsRebinding("rebinding");
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, dnsRebinding.category());
        assertEquals("rebinding", dnsRebinding.getMessage());

        OutboundHttpException redirectDenied = OutboundHttpException.redirectDenied("redirect denied");
        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, redirectDenied.category());
        assertEquals("redirect denied", redirectDenied.getMessage());

        OutboundHttpException timeout = OutboundHttpException.timeout("timed out");
        assertEquals(OutboundHttpException.Category.TIMEOUT, timeout.category());
        assertEquals("timed out", timeout.getMessage());

        OutboundHttpException tls = OutboundHttpException.tls("tls error");
        assertEquals(OutboundHttpException.Category.TLS_TRUST, tls.category());
        assertEquals("tls error", tls.getMessage());

        OutboundHttpException dns = OutboundHttpException.dns("dns fail");
        assertEquals(OutboundHttpException.Category.DNS_FAILURE, dns.category());
        assertEquals("dns fail", dns.getMessage());

        OutboundHttpException ioNoCause = OutboundHttpException.io("io fail");
        assertEquals(OutboundHttpException.Category.IO, ioNoCause.category());
        assertEquals("io fail", ioNoCause.getMessage());

        OutboundHttpException oversized = OutboundHttpException.oversized("payload too large");
        assertEquals(OutboundHttpException.Category.OVERSIZED_PAYLOAD, oversized.category());
        assertEquals("payload too large", oversized.getMessage());

        OutboundHttpException unsupportedMime = OutboundHttpException.unsupportedMime("bad mime");
        assertEquals(OutboundHttpException.Category.UNSUPPORTED_MIME, unsupportedMime.category());
        assertEquals("bad mime", unsupportedMime.getMessage());

        OutboundHttpException magicMismatch = OutboundHttpException.magicMismatch("magic header mismatch");
        assertEquals(OutboundHttpException.Category.MAGIC_MISMATCH, magicMismatch.category());
        assertEquals("magic header mismatch", magicMismatch.getMessage());

        IOException cause = new IOException("socket reset");
        OutboundHttpException ioWithCause = OutboundHttpException.io("io with cause", cause);
        assertEquals(OutboundHttpException.Category.IO, ioWithCause.category());
        assertEquals("io with cause", ioWithCause.getMessage());
        assertSame(cause, ioWithCause.getCause());
    }

    @Test
    @DisplayName("Direct constructor creates exception with category and cause")
    void directConstructor_withCause() {
        Exception cause = new RuntimeException("root");
        OutboundHttpException ex =
                new OutboundHttpException(OutboundHttpException.Category.REDIRECT_LIMIT, "limit reached", cause);
        assertEquals(OutboundHttpException.Category.REDIRECT_LIMIT, ex.category());
        assertEquals("limit reached", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
