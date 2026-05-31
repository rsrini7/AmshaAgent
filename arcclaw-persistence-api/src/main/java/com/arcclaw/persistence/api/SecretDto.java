package com.arcclaw.persistence.api;

/**
 * Transfer object for encrypted secrets.
 * §1.2: Core-persistence boundary.
 */
@SuppressWarnings("ArrayRecordComponent") // byte[] for cryptographic data is correct
public record SecretDto(String sessionId, String keyName, int encryptionKeyVersion, byte[] ciphertext, byte[] nonce) {}
