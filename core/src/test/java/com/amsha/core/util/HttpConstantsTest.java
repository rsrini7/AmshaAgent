package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpConstantsTest {

    @Test
    void recognizesLoopbackHostForms() {
        assertTrue(HttpConstants.isLoopbackHost("localhost"));
        assertTrue(HttpConstants.isLoopbackHost("127.0.0.1"));
        assertTrue(HttpConstants.isLoopbackHost("127.42.0.1"));
        assertTrue(HttpConstants.isLoopbackHost("::1"));
        assertTrue(HttpConstants.isLoopbackHost("0:0:0:0:0:0:0:1"));
        assertTrue(HttpConstants.isLoopbackHost("::ffff:127.0.0.1"));
        assertTrue(HttpConstants.isLoopbackHost("0:0:0:0:0:ffff:7f00:1"));
    }

    @Test
    void rejectsNonLoopbackHost() {
        assertFalse(HttpConstants.isLoopbackHost("192.168.1.10"));
        assertFalse(HttpConstants.isLoopbackHost("example.test"));
        assertFalse(HttpConstants.isLoopbackHost(null));
        assertFalse(HttpConstants.isLoopbackHost("  "));
    }
}
