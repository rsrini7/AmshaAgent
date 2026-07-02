package com.amsha.selfevolve.internal;

import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deduplicates extracted memories by content hash.
 * Uses SHA-256 for content hashing.
 */
@Singleton
public class ExtractionDeduplicator {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Computes a SHA-256 hex hash of the given content.
     */
    public String hashContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Determines whether two extracted memories are duplicates based on content hash.
     * Two memories are duplicates if they have the same record type and matching content hash.
     */
    public boolean isDuplicate(String existingHash, String newHash) {
        if (existingHash == null || newHash == null) {
            return false;
        }
        return existingHash.equals(newHash);
    }
}
