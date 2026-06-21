package com.amsha.persistence.api;

/**
 * Thrown by {@link CoreMemoryStorePort#upsert} when the
 * {@code expectedVersion} does not match the current stored version.
 * <p>
 * Implements CAS (Compare-And-Swap) semantics for Core Memory block updates.
 * Callers should re-read the block, resolve the conflict, and retry.
 */
public class VersionConflictException extends RuntimeException {

    private final String blockKey;
    private final int expectedVersion;
    private final int actualVersion;

    public VersionConflictException(String blockKey, int expectedVersion, int actualVersion) {
        super(String.format(
                "Version conflict for Core Memory block '%s': expected %d, actual %d",
                blockKey, expectedVersion, actualVersion));
        this.blockKey = blockKey;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String blockKey() {
        return blockKey;
    }

    public int expectedVersion() {
        return expectedVersion;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
