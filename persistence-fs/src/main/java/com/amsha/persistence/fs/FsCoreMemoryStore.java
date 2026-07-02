package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.amsha.core.memory.tiered.TieredBackend;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.VersionConflictException;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Filesystem implementation of Core Memory store.
 * Stores blocks as JSON per user in {dataDir}/core-memory/{userId}.json.
 */
@ApplicationScoped
@TieredBackend("fs")
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "fs")
public class FsCoreMemoryStore implements CoreMemoryStorePort {

    private static final Logger log = Logger.getLogger(FsCoreMemoryStore.class);
    private static final String DOMAIN = "core-memory";

    record BlockMap(List<CoreMemoryBlockDto> blocks) {}

    @Inject
    FilesystemFileStore store;

    @Override
    public List<CoreMemoryBlockDto> read(String userId) {
        return loadBlocks(userId);
    }

    @Override
    public void upsert(String userId, String blockKey, String content, int expectedVersion) {
        List<CoreMemoryBlockDto> blocks = loadBlocks(userId);
        Instant now = Instant.now();

        // Find existing block
        Optional<CoreMemoryBlockDto> existing =
                blocks.stream().filter(b -> blockKey.equals(b.blockKey())).findFirst();

        if (existing.isPresent()) {
            CoreMemoryBlockDto old = existing.get();
            if (old.version() != expectedVersion) {
                throw new VersionConflictException(blockKey, expectedVersion, old.version());
            }
            // Replace with new version
            blocks.remove(old);
            blocks.add(new CoreMemoryBlockDto(
                    old.blockId(), userId, blockKey, content, expectedVersion + 1, old.createdAt(), now));
        } else {
            // New block — version 1
            if (expectedVersion != 0) {
                throw new VersionConflictException(blockKey, expectedVersion, 0);
            }
            blocks.add(new CoreMemoryBlockDto(UUID.randomUUID().toString(), userId, blockKey, content, 1, now, now));
        }

        store.write(List.of(DOMAIN, userId + ".json"), new BlockMap(blocks));
        log.debugf(
                "[FsCoreMemory] Upserted block '%s' for user %s (v%d)",
                blockKey, userId, existing.isPresent() ? expectedVersion + 1 : 1);
    }

    @Override
    public void delete(String userId, String blockKey) {
        List<CoreMemoryBlockDto> blocks = loadBlocks(userId);
        boolean removed = blocks.removeIf(b -> blockKey.equals(b.blockKey()));
        if (removed) {
            store.write(List.of(DOMAIN, userId + ".json"), new BlockMap(blocks));
        }
    }

    @Override
    public Optional<CoreMemoryBlockDto> get(String userId, String blockKey) {
        return loadBlocks(userId).stream()
                .filter(b -> blockKey.equals(b.blockKey()))
                .findFirst();
    }

    private List<CoreMemoryBlockDto> loadBlocks(String userId) {
        Optional<BlockMap> map = store.readOptional(List.of(DOMAIN, userId + ".json"), BlockMap.class);
        return map.map(b -> new ArrayList<>(b.blocks())).orElseGet(ArrayList::new);
    }
}
