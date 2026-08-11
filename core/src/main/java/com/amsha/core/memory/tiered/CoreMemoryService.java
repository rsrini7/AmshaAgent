package com.amsha.core.memory.tiered;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.amsha.core.config.TieredMemoryConfig;
import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.CoreMemoryStorePort;
import com.amsha.persistence.api.VersionConflictException;

import org.jboss.logging.Logger;

@ApplicationScoped
public class CoreMemoryService {

    private static final Logger log = Logger.getLogger(CoreMemoryService.class);

    private static final Set<String> ALLOWED_BLOCK_KEYS =
            Set.of("user_profile", "active_task", "constraints", "project_context", "preferences");

    @Inject
    CoreMemoryStorePort coreStore;

    @Inject
    TieredMemoryConfig config;

    public List<CoreMemoryBlockDto> readAll(String userId) {
        return coreStore.read(userId);
    }

    public Optional<CoreMemoryBlockDto> get(String userId, String blockKey) {
        return coreStore.get(userId, blockKey);
    }

    public void upsert(String userId, String blockKey, String content) {
        if (!ALLOWED_BLOCK_KEYS.contains(blockKey)) {
            throw new IllegalArgumentException("Unknown block key: " + blockKey + ". Allowed: " + ALLOWED_BLOCK_KEYS);
        }

        String trimmed = truncate(content, config.coreMemory().maxCharsPerBlock());

        Optional<CoreMemoryBlockDto> existing = coreStore.get(userId, blockKey);
        int expectedVersion = existing.map(CoreMemoryBlockDto::version).orElse(0);

        if (existing.isEmpty()
                && coreStore.read(userId).size() >= config.coreMemory().maxBlocksPerUser()) {
            throw new IllegalStateException(
                    "Core Memory block limit reached: " + config.coreMemory().maxBlocksPerUser());
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                coreStore.upsert(userId, blockKey, trimmed, expectedVersion);
                return;
            } catch (VersionConflictException e) {
                log.debugf(
                        "CAS conflict on %s, attempt %d: expected=%d actual=%d",
                        blockKey, attempt + 1, e.expectedVersion(), e.actualVersion());
                expectedVersion = e.actualVersion();
            }
        }

        throw new IllegalStateException("Failed to upsert Core Memory block '" + blockKey + "' after 3 CAS retries");
    }

    /**
     * Atomically appends content to an existing block (or creates it).
     * Read-modify-write is performed inside the CAS retry loop to prevent lost updates
     * when two concurrent appends race on the same block.
     * <p>
     * <b>Scaling Model Boundary:</b> Agent-facing tools ({@code core_memory_append}) are strictly
     * limited to the 5-key {@link #ALLOWED_BLOCK_KEYS} allowlist. However, the {@code maxBlocksPerUser}
     * limit is enforced here to govern non-agent creation paths (e.g., Admin API,
     * Self-Evolve synthesis) that may introduce arbitrary block keys.
     * <p>
     * Unlike {@link #upsert}, append rejects content that would exceed the block size limit
     * rather than silently truncating. This forces the agent to deliberately curate the block
     * via {@code core_memory_replace} when it grows too large.
     *
     * @return the final content after append
     * @throws IllegalArgumentException if block key is unknown or combined content exceeds max size
     * @throws IllegalStateException if block limit reached or CAS retries exhausted
     */
    public String append(String userId, String blockKey, String content) {
        if (!ALLOWED_BLOCK_KEYS.contains(blockKey)) {
            throw new IllegalArgumentException("Unknown block key: " + blockKey + ". Allowed: " + ALLOWED_BLOCK_KEYS);
        }

        if (coreStore.get(userId, blockKey).isEmpty()
                && coreStore.read(userId).size() >= config.coreMemory().maxBlocksPerUser()) {
            throw new IllegalStateException(
                    "Core Memory block limit reached: " + config.coreMemory().maxBlocksPerUser());
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<CoreMemoryBlockDto> existing = coreStore.get(userId, blockKey);
            int expectedVersion = existing.map(CoreMemoryBlockDto::version).orElse(0);
            String current = existing.map(CoreMemoryBlockDto::content).orElse("");
            String combined = current.isEmpty() ? content : current + "\n" + content;

            if (combined.length() > config.coreMemory().maxCharsPerBlock()) {
                throw new IllegalArgumentException(String.format(
                        "Append would exceed max block size (%d chars). Current: %d, Append: %d. "
                                + "Use 'core_memory_replace' to curate the block first.",
                        config.coreMemory().maxCharsPerBlock(), current.length(), content.length()));
            }

            try {
                coreStore.upsert(userId, blockKey, combined, expectedVersion);
                return combined;
            } catch (VersionConflictException e) {
                log.debugf(
                        "CAS conflict on append %s, attempt %d: expected=%d actual=%d",
                        blockKey, attempt + 1, e.expectedVersion(), e.actualVersion());
            }
        }

        throw new IllegalStateException("Failed to append to Core Memory block '" + blockKey + "' after 3 CAS retries");
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
