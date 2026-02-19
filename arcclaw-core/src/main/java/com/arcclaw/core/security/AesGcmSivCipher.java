package com.arcclaw.core.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMSIVBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Encryption engine using AES-GCM-SIV (RFC 8452).
 * <p>
 * <strong>Why GCM-SIV?</strong>
 * Standard GCM fails catastrophically if a (Key, Nonce) pair is ever reused
 * (the "Nonce-Misuse" problem),
 * revealing the XOR of plaintexts. GCM-SIV is "nonce-misuse resistant": reusing
 * a nonce leaks ONLY
 * whether the same plaintext was sent again, but protects the content. This
 * adds a critical safety margin.
 * <p>
 * <strong>AAD Binding:</strong>
 * All encryption MUST provide Associated Authenticated Data (AAD). In ArcClaw,
 * we bind the
 * <code>keyName</code> (e.g., "OPENAI_API_KEY") as AAD. This prevents "Key
 * Swapping" attacks
 * where a valid ciphertext for one key is moved to another slot.
 */
@ApplicationScoped
public class AesGcmSivCipher {

    private static final int NONCE_LENGTH = 12; // Standard 96-bit nonce
    private static final int MAC_SIZE_BITS = 128; // Standard 128-bit authentication tag

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts a secret value.
     *
     * @param secret The plaintext bytes.
     * @param key    The session's derived encryption key.
     * @param aad    Associated Data (MUST be the keyName, e.g., "OPENAI_API_KEY").
     * @return A Result object containing the ciphertext and the nonce used.
     */
    public CipherResult encrypt(byte[] secret, VaultKey key, String aad) {
        // 1. Generate Nonce
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);

        // 2. Prepare Engine
        GCMSIVBlockCipher cipher = new GCMSIVBlockCipher(new AESEngine());
        byte[] aadBytes = aad.getBytes(StandardCharsets.UTF_8);

        AEADParameters params = new AEADParameters(
                new KeyParameter(key.get()),
                MAC_SIZE_BITS,
                nonce,
                aadBytes);

        cipher.init(true, params); // true = encrypt

        // 3. Encrypt
        byte[] ciphertext = new byte[cipher.getOutputSize(secret.length)];
        try {
            int len = cipher.processBytes(secret, 0, secret.length, ciphertext, 0);
            cipher.doFinal(ciphertext, len);
        } catch (InvalidCipherTextException e) {
            throw new SecurityException("Encryption failed (this should theoretically not happen)", e);
        }

        return new CipherResult(ciphertext, nonce);
    }

    /**
     * Decrypts a secret value.
     *
     * @param ciphertext The encrypted bytes.
     * @param nonce      The nonce used during encryption.
     * @param key        The session's derived encryption key.
     * @param aad        Associated Data (MUST match the value used during
     *                   encryption).
     * @return The plaintext bytes.
     * @throws SecurityException if decryption fails (tampering) or AAD mismatch
     *                           (key swapping).
     */
    public byte[] decrypt(byte[] ciphertext, byte[] nonce, VaultKey key, String aad) {
        // 1. Prepare Engine
        GCMSIVBlockCipher cipher = new GCMSIVBlockCipher(new AESEngine());
        byte[] aadBytes = aad.getBytes(StandardCharsets.UTF_8);

        AEADParameters params = new AEADParameters(
                new KeyParameter(key.get()),
                MAC_SIZE_BITS,
                nonce,
                aadBytes);

        cipher.init(false, params); // false = decrypt

        // 2. Decrypt
        byte[] plaintext = new byte[cipher.getOutputSize(ciphertext.length)];
        try {
            int len = cipher.processBytes(ciphertext, 0, ciphertext.length, plaintext, 0);
            cipher.doFinal(plaintext, len);
        } catch (InvalidCipherTextException e) {
            // This exception is thrown if the Authentication Tag validation fails.
            // This means either the ciphertext was tampered with, OR the AAD (keyName) is
            // wrong.
            throw new SecurityException("Decryption failed: Integrity check or AAD binding mismatch", e);
        }

        return plaintext;
    }

    /**
     * Simple DTO to hold encryption output.
     */
    public record CipherResult(byte[] ciphertext, byte[] nonce) {
    }
}
