package com.amsha.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.Test;

/**
 * Round-trip and tamper tests for AES-GCM-SIV (relocated with BouncyCastle in S7).
 * AAD binding must reject ciphertext moved between vault slots ("key swapping").
 */
class AesGcmSivCipherTest {

    private static final String KEY_NAME = "OPENAI_API_KEY";

    @Test
    void roundTripRecoversPlaintext() {
        var cipher = new AesGcmSivCipher();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        try (VaultKey key = new VaultKey(keyBytes)) {
            byte[] secret = "sk-test-secret-value".getBytes(StandardCharsets.UTF_8);

            var result = cipher.encrypt(secret, key, KEY_NAME);
            byte[] plaintext = cipher.decrypt(result.ciphertext(), result.nonce(), key, KEY_NAME);

            assertArrayEquals(secret, plaintext);
        }
    }

    @Test
    void aadMismatchIsRejectedAsSecurityException() {
        var cipher = new AesGcmSivCipher();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        try (VaultKey key = new VaultKey(keyBytes)) {
            byte[] secret = {1, 2, 3};
            var result = cipher.encrypt(secret, key, KEY_NAME);

            assertThrows(
                    SecurityException.class,
                    () -> cipher.decrypt(result.ciphertext(), result.nonce(), key, "OTHER_KEY"));
        }
    }

    @Test
    void tamperedCiphertextIsRejected() {
        var cipher = new AesGcmSivCipher();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        try (VaultKey key = new VaultKey(keyBytes)) {
            var result = cipher.encrypt(new byte[] {9, 9, 9}, key, KEY_NAME);
            // Accessors return defensive copies: tamper one retrieved copy, then decrypt THAT copy.
            byte[] tampered = result.ciphertext();
            tampered[0] ^= 0x01;

            assertThrows(SecurityException.class, () -> cipher.decrypt(tampered, result.nonce(), key, KEY_NAME));
        }
    }

    @Test
    void cipherResultAccessorsReturnDefensiveCopies() {
        var cipher = new AesGcmSivCipher();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        try (VaultKey key = new VaultKey(keyBytes)) {
            var result = cipher.encrypt(new byte[] {1}, key, KEY_NAME);

            byte[] nonceView = result.nonce();
            nonceView[0] ^= 0x55;
            assertFalse(Arrays.equals(nonceView, result.nonce()), "nonce accessor must return a fresh copy per call");
        }
    }
}
