package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NetworkPolicyViolationTest {

    @Test
    void validatesExceptionMessage() {
        NetworkPolicyViolation ex = new NetworkPolicyViolation("Outbound network policy violation: host=evil.com");
        assertEquals("Outbound network policy violation: host=evil.com", ex.getMessage());
    }
}
