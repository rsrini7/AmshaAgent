package com.arcclaw.core.memory.tiered;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.config.TieredMemoryConfig;
import com.arcclaw.persistence.api.CoreMemoryBlockDto;
import com.arcclaw.persistence.api.CoreMemoryStorePort;
import com.arcclaw.persistence.api.VersionConflictException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CoreMemoryService {

    private static final Logger log = Logger.getLogger(CoreMemoryService.class);

    private static final Set<String> ALLOWED_BLOCK_KEYS = Set.of(
        "user_profile", "active_task", "constraints", "project_context", "preferences"
    );

    @Inject
    CoreMemoryStorePort coreStore;

    @Inject
    TieredMemoryConfig config;

    @ConfigProperty(name = "arcclaw.memory.tiered.core-memory.max-blocks-per-user", defaultValue = "20")
    int maxBlocksPerUser;

    @ConfigProperty(name = "arcclaw.memory.tiered.core-memory.max-chars-per-block", defaultValue = "2000")
    int maxCharsPerBlock;

    public List<CoreMemoryBlockDto> readAll(String userId) {
        return coreStore.read(userId);
    }

    public Optional<CoreMemoryBlockDto> get(String userId, String blockKey) {
        return coreStore.get(userId, blockKey);
    }

    public void upsert(String userId, String blockKey, String content) {
        if (!ALLOWED_BLOCK_KEYS.contains(blockKey)) {
            throw new IllegalArgumentException("Unknown block key: " + blockKey
                + ". Allowed: " + ALLOWED_BLOCK_KEYS);
        }

        String trimmed = truncate(content, maxCharsPerBlock);

        Optional<CoreMemoryBlockDto> existing = coreStore.get(userId, blockKey);
        int expectedVersion = existing.map(CoreMemoryBlockDto::version).orElse(0);

        if (existing.isEmpty() && coreStore.read(userId).size() >= maxBlocksPerUser) {
            throw new IllegalStateException(
                "Core Memory block limit reached: " + maxBlocksPerUser);
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                coreStore.upsert(userId, blockKey, trimmed, expectedVersion);
                return;
            } catch (VersionConflictException e) {
                log.debugf("CAS conflict on %s, attempt %d: expected=%d actual=%d",
                    blockKey, attempt + 1, e.expectedVersion(), e.actualVersion());
                expectedVersion = e.actualVersion();
            }
        }

        throw new IllegalStateException(
            "Failed to upsert Core Memory block '" + blockKey + "' after 3 CAS retries");
    }

    /**
     * Atomically appends content to an existing block (or creates it).
     * Read-modify-write is performed inside the CAS retry loop to prevent lost updates
     * when two concurrent appends race on the same block.
     *
     * @return the final content after append
     */
    public String append(String userId, String blockKey, String content) {
        if (!ALLOWED_BLOCK_KEYS.contains(blockKey)) {
            throw new IllegalArgumentException("Unknown block key: " + blockKey
                + ". Allowed: " + ALLOWED_BLOCK_KEYS);
        }

        if (coreStore.get(userId, blockKey).isEmpty()
            && coreStore.read(userId).size() >= maxBlocksPerUser) {
            throw new IllegalStateException(
                "Core Memory block limit reached: " + maxBlocksPerUser);
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<CoreMemoryBlockDto> existing = coreStore.get(userId, blockKey);
            int expectedVersion = existing.map(CoreMemoryBlockDto::version).orElse(0);
            String current = existing.map(CoreMemoryBlockDto::content).orElse("");
            String newContent = current.isEmpty()
                ? truncate(content, maxCharsPerBlock)
                : truncate(current + "\n" + content, maxCharsPerBlock);

            try {
                coreStore.upsert(userId, blockKey, newContent, expectedVersion);
                return newContent;
            } catch (VersionConflictException e) {
                log.debugf("CAS conflict on append %s, attempt %d: expected=%d actual=%d",
                    blockKey, attempt + 1, e.expectedVersion(), e.actualVersion());
            }
        }

        throw new IllegalStateException(
            "Failed to append to Core Memory block '" + blockKey + "' after 3 CAS retries");
    }

    public void delete(String userId, String blockKey) {
        coreStore.delete(userId, blockKey);
    }

    private static String truncate(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars - 3) + "...";
    }
}
