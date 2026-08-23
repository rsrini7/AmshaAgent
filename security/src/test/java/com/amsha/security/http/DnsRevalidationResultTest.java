package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Pins the DNS-rebinding revalidation contract (relocated with governed HTTP in S7). */
class DnsRevalidationResultTest {

    @Test
    void okIsSafeWithoutReason() {
        var result = DnsRevalidationResult.ok();
        assertTrue(result.safe());
        assertNull(result.reason());
    }

    @Test
    void unsafeCarriesReason() {
        var result = DnsRevalidationResult.unsafe("resolved to loopback");
        assertFalse(result.safe());
        assertEquals("resolved to loopback", result.reason());
    }
}
