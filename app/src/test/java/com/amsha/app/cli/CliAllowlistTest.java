package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CliAllowlistTest {

    private final CliAllowlist allowlist = new CliAllowlist();

    @Test
    void testCliAllowlistContracts() {
        assertTrue(allowlist.isAllowed("user1", "alice"));
        assertFalse(allowlist.isSilentBlock());
        assertEquals("Access denied.", allowlist.getBlockResponse());
    }
}
