package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

import com.amsha.persistence.api.OnboardingExtensionStateDto;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsOnboardingExtensionStateStoreAdapterTest {

    private Path tempDir;
    private FsOnboardingExtensionStateStoreAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-ext-test-");
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
        adapter = new FsOnboardingExtensionStateStoreAdapter();
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

    private static OnboardingExtensionStateDto state(String draftId, String extId, String outcome) {
        Instant now = Instant.now();
        return new OnboardingExtensionStateDto(draftId, extId, "v1", "WORKSPACE", outcome, now, now);
    }

    @Test
    void saveFindListAndDeleteByDraft() {
        adapter.save(state("d-1", "ext-a", OnboardingExtensionStateDto.OUTCOME_CONFIGURED));
        adapter.save(state("d-1", "ext-b", OnboardingExtensionStateDto.OUTCOME_SKIPPED));
        adapter.save(state("d-2", "ext-a", OnboardingExtensionStateDto.OUTCOME_CONFIGURED));

        assertEquals(2, adapter.listByDraft("d-1").size());
        assertEquals(1, adapter.listByDraft("d-2").size());

        Optional<OnboardingExtensionStateDto> found = adapter.findByDraftAndExtension("d-1", "ext-a");
        assertTrue(found.isPresent());
        assertEquals(OnboardingExtensionStateDto.OUTCOME_CONFIGURED, found.get().outcome());

        // update overwrites the same (draft, extension) file.
        adapter.update(state("d-1", "ext-a", OnboardingExtensionStateDto.OUTCOME_SKIPPED), 0);
        assertEquals(
                OnboardingExtensionStateDto.OUTCOME_SKIPPED,
                adapter.findByDraftAndExtension("d-1", "ext-a").orElseThrow().outcome());
        assertEquals(2, adapter.listByDraft("d-1").size());
    }

    @Test
    void deleteByDraftRemovesOnlyThatDraft() {
        adapter.save(state("d-1", "ext-a", OnboardingExtensionStateDto.OUTCOME_CONFIGURED));
        adapter.save(state("d-1", "ext-b", OnboardingExtensionStateDto.OUTCOME_SKIPPED));
        assertEquals(2, adapter.deleteByDraft("d-1"));
        assertTrue(adapter.listByDraft("d-1").isEmpty());
    }
}
