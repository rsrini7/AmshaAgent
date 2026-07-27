package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingCommitReceiptDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsOnboardingCommitReceiptStoreAdapterTest {

    private Path tempDir;
    private FsOnboardingCommitReceiptStoreAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-receipt-test-");
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
        adapter = new FsOnboardingCommitReceiptStoreAdapter();
        adapter.store = fileStore;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) return;
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static OnboardingCommitReceiptDto receipt(String id, String draftId, String opId, String state) {
        Instant now = Instant.now();
        return new OnboardingCommitReceiptDto(id, draftId, "ext-" + id, opId, "{\"ok\":true}", state, now, now);
    }

    @Test
    void upsertFindAndIdempotentOverwrite() {
        adapter.upsert(receipt("r1", "d-1", "op-1", "COMMITTED"));
        Optional<OnboardingCommitReceiptDto> found = adapter.findByOperationId("op-1");
        assertTrue(found.isPresent());
        assertEquals("d-1", found.get().draftId());

        adapter.upsert(receipt("r1", "d-1", "op-1", "COMPENSATED"));
        assertEquals(1, adapter.listByDraftId("d-1").size());
        assertEquals(
                "COMPENSATED", adapter.findByOperationId("op-1").orElseThrow().state());
    }

    @Test
    void listByDraftAndDelete() {
        adapter.upsert(receipt("r1", "d-1", "op-1", "PREPARED"));
        adapter.upsert(receipt("r2", "d-1", "op-2", "COMMITTED"));
        adapter.upsert(receipt("r3", "d-2", "op-3", "COMMITTED"));

        assertEquals(2, adapter.listByDraftId("d-1").size());
        assertEquals(1, adapter.listByDraftId("d-2").size());

        assertEquals(2, adapter.deleteByDraftId("d-1"));
        assertTrue(adapter.listByDraftId("d-1").isEmpty());
        assertEquals(1, adapter.listByDraftId("d-2").size());
    }
}
