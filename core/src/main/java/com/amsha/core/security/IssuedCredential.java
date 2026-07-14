package com.amsha.core.security;

/**
 * Represents the issued bootstrap credential containing both the raw key and its hmac digest.
 */
public record IssuedCredential(String rawKey, String keyDigest) {}
