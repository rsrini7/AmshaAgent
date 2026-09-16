package com.amsha.persistence.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Companion to {@link FsVectorIndexMetadataAdapterTest}, which only walks the happy path (initialize →
 * mismatch → re-initialize) for the global namespace with a relative index directory.
 *
 * <p>That leaves the entire failure surface unexecuted: the {@code catch (RuntimeException)} that degrades a
 * namespace to {@code UNAVAILABLE} instead of failing startup, the manifest read failure, the manifest
 * identity check, the write failure, {@code markUnavailable}, and the two other branches of the manifest-path
 * resolution (absolute index directory; non-global namespace; a relative directory that escapes the data
 * root). This class drives all of them against a real temp directory.
 */
@SuppressWarnings("java:S5443") // Test-only temp directory.
class FsVectorIndexMetadataAdapterDeepTest {

    private Path tempDir;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("amsha-fs-vector-meta-deep-");
        objectMapper = new ObjectMapper();
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

    // ─── Degradation instead of startup failure ────────────────

    @Test
    void verifyOrInitializeDegradesToUnavailableWhenTheManifestCannotBeParsed() throws IOException {
        writeManifestFile("{not json");

        FsVectorIndexMetadataAdapter adapter = adapter("lucene-index");
        VectorIndexCompatibility result =
                adapter.verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, descriptor("revision-1"), true);

        assertEquals(VectorIndexCompatibility.Status.UNAVAILABLE, result.status());
        assertFalse(result.vectorEnabled());
        assertFalse(
                adapter.isVectorEnabled(VectorIndexNamespace.RAG_GLOBAL),
                "the degraded result is recorded for later readers");
        assertEquals(result, adapter.compatibilitySnapshot().get(VectorIndexNamespace.RAG_GLOBAL));
    }

    @Test
    void verifyOrInitializeDegradesToUnavailableWhenTheManifestChecksumDoesNotMatch() throws IOException {
        EmbeddingIndexDescriptor descriptor = descriptor("revision-1");
        writeManifestFile("""
                {"manifestVersion":1,"namespace":"rag-global","descriptor":%s,"checksum":"tampered"}""".formatted(objectMapper.writeValueAsString(descriptor)));

        VectorIndexCompatibility result =
                adapter("lucene-index").verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, descriptor, true);

        assertEquals(
                VectorIndexCompatibility.Status.UNAVAILABLE,
                result.status(),
                "a tampered manifest must not be trusted");
    }

    @Test
    void verifyOrInitializeDegradesToUnavailableWhenTheManifestDirectoryIsAFile() throws IOException {
        Files.writeString(tempDir.resolve("lucene-index"), "not a directory");

        VectorIndexCompatibility result = adapter("lucene-index")
                .verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, descriptor("revision-1"), true);

        assertEquals(
                VectorIndexCompatibility.Status.UNAVAILABLE,
                result.status(),
                "an unwritable manifest path degrades rather than aborting");
    }

    // ─── markUnavailable ───────────────────────────────────────

    @Test
    void markUnavailableRecordsAnUnavailableResult() {
        FsVectorIndexMetadataAdapter adapter = adapter("lucene-index");
        EmbeddingIndexDescriptor active = descriptor("revision-1");

        VectorIndexCompatibility result = adapter.markUnavailable(VectorIndexNamespace.ARCHIVAL, active);

        assertEquals(VectorIndexCompatibility.Status.UNAVAILABLE, result.status());
        assertFalse(result.vectorEnabled());
        assertEquals(active.fingerprint(), result.activeFingerprint());
        assertEquals(VectorIndexNamespace.ARCHIVAL, result.namespace());
        assertFalse(adapter.isVectorEnabled(VectorIndexNamespace.ARCHIVAL));
        assertEquals(result, adapter.compatibilitySnapshot().get(VectorIndexNamespace.ARCHIVAL));
    }

    @Test
    void isVectorEnabledIsFalseForANamespaceThatWasNeverAssessed() {
        assertFalse(adapter("lucene-index").isVectorEnabled(VectorIndexNamespace.ARCHIVAL));
    }

    // ─── Manifest path resolution ──────────────────────────────

    @Test
    void anAbsoluteLuceneIndexDirIsUsedVerbatim() {
        Path absoluteIndex = tempDir.resolve("absolute-index");

        VectorIndexCompatibility result = adapter(absoluteIndex.toString())
                .verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, descriptor("revision-1"), true);

        assertEquals(VectorIndexCompatibility.Status.INITIALIZED, result.status());
        assertTrue(
                Files.isRegularFile(absoluteIndex.resolve("index_descriptor.json")),
                "the manifest lands under the absolute directory, not under dataDir");
    }

    @Test
    void aNonGlobalNamespaceLivesUnderTheVectorIndexesDirectory() {
        VectorIndexCompatibility result = adapter("lucene-index")
                .verifyOrInitialize(VectorIndexNamespace.ARCHIVAL, descriptor("revision-1"), true);

        assertEquals(VectorIndexCompatibility.Status.INITIALIZED, result.status());
        assertTrue(Files.isRegularFile(
                tempDir.resolve("vector-indexes").resolve("archival").resolve("index_descriptor.json")));
        assertFalse(
                Files.exists(tempDir.resolve("lucene-index").resolve("index_descriptor.json")),
                "the global directory is not reused for a scoped namespace");
    }

    @Test
    void aRelativeLuceneIndexDirThatEscapesTheDataRootIsRejected() {
        VectorIndexCompatibility result = adapter("../escaped-index")
                .verifyOrInitialize(VectorIndexNamespace.RAG_GLOBAL, descriptor("revision-1"), true);

        assertEquals(
                VectorIndexCompatibility.Status.UNAVAILABLE,
                result.status(),
                "a manifest path that escapes the data root must not be trusted");
        assertFalse(
                Files.exists(tempDir.getParent().resolve("escaped-index")), "nothing is written outside the data root");
    }

    // ─── Helpers ───────────────────────────────────────────────

    private void writeManifestFile(String content) throws IOException {
        Path manifest = tempDir.resolve("lucene-index").resolve("index_descriptor.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, content);
    }

    private FsVectorIndexMetadataAdapter adapter(String luceneIndexDir) {
        FsVectorIndexMetadataAdapter adapter = new FsVectorIndexMetadataAdapter();
        adapter.properties = props(luceneIndexDir);
        adapter.objectMapper = objectMapper;
        return adapter;
    }

    private FsStoreProperties props(String luceneIndexDir) {
        return new FsStoreProperties() {
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
                return luceneIndexDir;
            }

            @Override
            public int vectorDimension() {
                return 4;
            }
        };
    }

    private static EmbeddingIndexDescriptor descriptor(String revision) {
        return EmbeddingIndexDescriptor.create(
                "test",
                "test-space",
                revision,
                "tokenizer-1",
                "preprocessing-1",
                "MASKED_MEAN",
                true,
                4,
                "FLOAT32",
                "FIRST_MAX_TOKENS",
                "v1",
                512,
                0,
                "DIRECT");
    }
}
