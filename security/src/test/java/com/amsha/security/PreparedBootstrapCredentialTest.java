package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the prepared bootstrap credential moved from core (S7): accessors,
 * exactly-once close semantics, and secret-byte zeroing.
 */
class PreparedBootstrapCredentialTest {

    @Test
    void preparedCredentialZerosBytesExactlyOnce() {
        byte[] key = {1, 2, 3};
        PreparedBootstrapCredential credential = new PreparedBootstrapCredential("owner", "raw", key, "digest");
        assertAll(
                () -> assertEquals("owner", credential.ownerId()),
                () -> assertEquals("raw", credential.rawKey()),
                () -> assertSame(key, credential.rawKeyBytes()),
                () -> assertEquals("digest", credential.keyDigest()),
                () -> assertFalse(credential.isClosed()));
        credential.close();
        credential.close();
        assertTrue(credential.isClosed());
        assertArrayEquals(new byte[3], key);
    }
}
