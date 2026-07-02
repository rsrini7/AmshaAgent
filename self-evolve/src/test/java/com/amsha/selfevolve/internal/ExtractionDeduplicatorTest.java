package com.amsha.selfevolve.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExtractionDeduplicatorTest {

    private ExtractionDeduplicator dedup;

    @BeforeEach
    void setUp() {
        dedup = new ExtractionDeduplicator();
    }

    @Test
    void hashContent_sameInput_returnsSameHash() {
        String hash1 = dedup.hashContent("Hello, world!");
        String hash2 = dedup.hashContent("Hello, world!");
        assertEquals(hash1, hash2);
    }

    @Test
    void hashContent_differentInput_returnsDifferentHash() {
        String hash1 = dedup.hashContent("Hello, world!");
        String hash2 = dedup.hashContent("Goodbye, world!");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void hashContent_nullInput_returnsEmpty() {
        assertEquals("", dedup.hashContent(null));
    }

    @Test
    void hashContent_blankInput_returnsEmpty() {
        assertEquals("", dedup.hashContent("   "));
    }

    @Test
    void hashContent_lengthIs64() {
        String hash = dedup.hashContent("test content");
        assertEquals(64, hash.length());
    }

    @Test
    void isDuplicate_matchingHashes_returnsTrue() {
        String hash = dedup.hashContent("same content");
        assertTrue(dedup.isDuplicate(hash, hash));
    }

    @Test
    void isDuplicate_differentHashes_returnsFalse() {
        String hash1 = dedup.hashContent("content A");
        String hash2 = dedup.hashContent("content B");
        assertFalse(dedup.isDuplicate(hash1, hash2));
    }

    @Test
    void isDuplicate_nullFirst_returnsFalse() {
        assertFalse(dedup.isDuplicate(null, "hash"));
    }

    @Test
    void isDuplicate_nullSecond_returnsFalse() {
        assertFalse(dedup.isDuplicate("hash", null));
    }

    @Test
    void isDuplicate_bothNull_returnsFalse() {
        assertFalse(dedup.isDuplicate(null, null));
    }
}
