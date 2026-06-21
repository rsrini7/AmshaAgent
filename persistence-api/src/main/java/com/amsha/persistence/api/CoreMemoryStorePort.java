package com.amsha.persistence.api;

import java.util.List;
import java.util.Optional;

/**
 * Port for Core Memory operations — small curated blocks always rendered into the system prompt.
 * <p>
 * Updates use CAS (Compare-And-Swap) versioning — {@code expectedVersion} must match
 * the stored version, or {@link VersionConflictException} is thrown.
 */
public interface CoreMemoryStorePort {

    List<CoreMemoryBlockDto> read(String userId);

    /**
     * Upserts a Core Memory block with CAS versioning.
     * <p>
     * If a block exists for {@code (userId, blockKey)}, the stored {@code version}
     * must equal {@code expectedVersion}. If versions differ, a
     * {@link VersionConflictException} is thrown so the caller can re-read
     * and reconcile before retrying.
     * <p>
     * If no block exists, a new block is created with version 1.
     *
     * @throws VersionConflictException if stored version != expectedVersion
     */
    void upsert(String userId, String blockKey, String content, int expectedVersion);

    void delete(String userId, String blockKey);

    Optional<CoreMemoryBlockDto> get(String userId, String blockKey);
}
