package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.amsha.persistence.api.CoreMemoryBlockDto;
import com.amsha.persistence.api.VersionConflictException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real-filesystem coverage for {@link FsCoreMemoryStore}.
 *
 * <p>The only existing exercise of this adapter is the {@code coreMemoryCreatesUpdatesReadsAndRejectsVersionConflicts}
 * arm of {@link FsCoverageGapTest}, which stubs {@code readOptional} to return {@link java.util.Optional#empty()}
 * for every call. That means the whole update path — reading back an existing block, the optimistic version
 * check, the replace-not-append rewrite, and the {@code delete} write-back — never runs: a store that never
 * has anything in it cannot exercise any of them. This class binds the adapter to a real store rooted in a
 * temp directory so the create → update → read → delete lifecycle is genuinely driven.
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsCoreMemoryStoreDeepTest {

    private Path tempDir;
    private FsCoreMemoryStore coreStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-core-memory-deep-");
        FsStoreProperties props = new FsStoreProperties() {
            @Override
            public String dataDir() {
                return tempDir.toString();
            }

            @Override
            public int lockTimeoutSeconds() {
                return 5;
            }

            @Override
            public String luceneIndexDir() {
                return "lucene-index";
            }

            @Override
            public int vectorDimension() {
                return 768;
            }
        };
        FilesystemFileStore fileStore = new FilesystemFileStore(props);
        fileStore.ensureDataDir();

        coreStore = new FsCoreMemoryStore();
        coreStore.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Test
    void upsertCreatesThenUpdatesAVersionedBlock() {
        coreStore.upsert("u1", "profile", "one", 0);
        CoreMemoryBlockDto created = coreStore.get("u1", "profile").orElseThrow();
        assertEquals("one", created.content());
        assertEquals(1, created.version(), "a new block starts at version 1");
        assertEquals(1, coreStore.read("u1").size());

        coreStore.upsert("u1", "profile", "two", 1);
        CoreMemoryBlockDto updated = coreStore.get("u1", "profile").orElseThrow();
        assertEquals("two", updated.content());
        assertEquals(2, updated.version(), "the stored version is expectedVersion + 1");
        assertEquals(created.blockId(), updated.blockId(), "the update keeps the original block id");
        assertEquals(created.createdAt(), updated.createdAt(), "and the original creation time");
        assertEquals(1, coreStore.read("u1").size(), "the block is replaced, not appended");
    }

    @Test
    void upsertRejectsAStaleVersionOnAnExistingBlock() {
        coreStore.upsert("u1", "profile", "one", 0);

        assertThrows(VersionConflictException.class, () -> coreStore.upsert("u1", "profile", "two", 7));

        assertEquals(
                1,
                coreStore.get("u1", "profile").orElseThrow().version(),
                "the rejected write left the block untouched");
        assertEquals("one", coreStore.get("u1", "profile").orElseThrow().content());
    }

    @Test
    void upsertRejectsANonZeroVersionForANewBlock() {
        assertThrows(VersionConflictException.class, () -> coreStore.upsert("u2", "brand-new", "content", 3));

        assertTrue(coreStore.get("u2", "brand-new").isEmpty(), "nothing was persisted");
        assertTrue(coreStore.read("u2").isEmpty());
    }

    @Test
    void readReturnsEveryBlockAndAnUnknownUserIsEmpty() {
        assertTrue(coreStore.read("nobody").isEmpty(), "an absent user file reads as an empty block list");

        coreStore.upsert("u1", "a", "one", 0);
        coreStore.upsert("u1", "b", "two", 0);

        assertEquals(List.of("a", "b"), keys(coreStore.read("u1")));
    }

    @Test
    void deleteRemovesABlockAndIsANoOpForAnUnknownKey() {
        coreStore.upsert("u1", "a", "one", 0);
        coreStore.upsert("u1", "b", "two", 0);

        coreStore.delete("u1", "b");

        assertEquals(List.of("a"), keys(coreStore.read("u1")));
        assertTrue(coreStore.get("u1", "b").isEmpty());

        coreStore.delete("u1", "no-such-key");
        assertEquals(1, coreStore.read("u1").size(), "an unknown key rewrites nothing");
    }

    @Test
    void deleteIsANoOpForAUserWithNoBlocksAtAll() {
        coreStore.delete("nobody", "profile");

        assertTrue(coreStore.read("nobody").isEmpty());
    }

    // ─── Helpers ───────────────────────────────────────────────

    private static List<String> keys(List<CoreMemoryBlockDto> blocks) {
        return blocks.stream().map(CoreMemoryBlockDto::blockKey).toList();
    }
}
