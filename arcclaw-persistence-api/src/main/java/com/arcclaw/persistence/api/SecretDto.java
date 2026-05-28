package com.arcclaw.persistence.api;

/**
 * Transfer object for encrypted secrets.
 * §1.2: Core-persistence boundary.
 */
public record SecretDto(String sessionId, String keyName, int encryptionKeyVersion, byte[] ciphertext, byte[] nonce) {}
