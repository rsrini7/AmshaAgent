package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.app.cli.ModelInstaller.ModelCatalogEntry;
import com.amsha.app.cli.ModelInstaller.ModelFileSpec;
import com.amsha.app.cli.ModelInstaller.ModelStatus;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ModelInstaller}.
 *
 * <p>The installer's whole job is integrity: download, verify size, verify SHA-256, then atomically publish.
 * Every one of those checks therefore gets a case that <em>must</em> fail, driven through a real local HTTP
 * server rather than a mocked client — the streaming/digest/partial-file dance is exactly the part that a
 * mock would skip.
 *
 * <p>The catalog's real entries point at multi-hundred-megabyte Hugging Face artifacts, so the tests build
 * their own tiny {@link ModelCatalogEntry} whose bytes they control.
 *
 * <p><strong>Ambient environment.</strong> {@link ModelInstaller#resolveModelRoot()} reads
 * {@code System.getenv("AMSHA_ONNX_MODEL_ROOT")} <em>before</em> {@code System.getProperty("amsha.onnx.model-root")}
 * — the environment deliberately outranks the system property (the two lookups inside that same method do not
 * even agree: {@code AMSHA_DATA_DIR} is read property-first). A developer who has exported that variable
 * therefore cannot steer resolution from a test at all, and the property-driven cases below are
 * {@linkplain DisabledIfEnvironmentVariable skipped} instead of silently asserting the wrong root.
 * {@link #environmentVariableOutranksTheModelRootProperty()} pins the precedence itself, and runs only on a
 * machine where the variable is set.
 */
class ModelInstallerTest {

    private static final byte[] PAYLOAD = "amsha-model-payload".getBytes(StandardCharsets.UTF_8);
    private static final String FILE_NAME = "model.onnx";

    private static final String MODEL_ROOT_PROPERTY = "amsha.onnx.model-root";
    private static final String DATA_DIR_PROPERTY = "AMSHA_DATA_DIR";
    private static final String MODEL_ROOT_ENV = "AMSHA_ONNX_MODEL_ROOT";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();

    private String originalModelRoot;
    private String originalDataDir;

    @TempDir
    Path modelRoot;

    @BeforeEach
    void startServer() throws IOException {
        // Snapshot rather than assume: clearing in @AfterEach would clobber a value another class relies on.
        originalModelRoot = System.getProperty(MODEL_ROOT_PROPERTY);
        originalDataDir = System.getProperty(DATA_DIR_PROPERTY);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            int status;
            switch (path) {
                case "/ok.bin" -> {
                    body = PAYLOAD;
                    status = 200;
                }
                case "/wrong-size.bin" -> {
                    body = "short".getBytes(StandardCharsets.UTF_8);
                    status = 200;
                }
                case "/wrong-hash.bin" -> {
                    body = new byte[PAYLOAD.length]; // right length, wrong content
                    status = 200;
                }
                case "/slow.bin" -> {
                    // Delay before responding so the client is still blocked inside send().
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    body = PAYLOAD;
                    status = 200;
                }
                default -> {
                    body = "not found".getBytes(StandardCharsets.UTF_8);
                    status = 404;
                }
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        restore(MODEL_ROOT_PROPERTY, originalModelRoot);
        restore(DATA_DIR_PROPERTY, originalDataDir);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A one-file catalog entry whose expected bytes/hash describe {@code content}. */
    private ModelCatalogEntry entryFor(String remotePath, byte[] content) {
        return new ModelCatalogEntry(
                "test-bundle",
                List.of("testbundle", "tiny"),
                "synthetic bundle for tests",
                "1 B",
                List.of(new ModelFileSpec(FILE_NAME, baseUrl + remotePath, content.length, sha256Hex(content))),
                "{\"bundleId\":\"test-bundle\"}");
    }

    private static PrintStream sink(ByteArrayOutputStream captured) {
        return new PrintStream(captured, true, StandardCharsets.UTF_8);
    }

    /** Writes a valid bundle (file + manifest) for {@code entry} under the temp model root. */
    private void writeValidBundle(ModelCatalogEntry entry, byte[] content) throws IOException {
        Path bundleDir = modelRoot.resolve(entry.id());
        Files.createDirectories(bundleDir);
        Files.write(bundleDir.resolve(FILE_NAME), content);
        Files.writeString(bundleDir.resolve("manifest.json"), entry.manifestJson());
    }

    // ---------- catalog ----------

    @Test
    @DisplayName("the catalog exposes the three shipped bundles")
    void catalogExposesThreeBundles() {
        List<ModelCatalogEntry> models = ModelInstaller.allModels();

        assertEquals(3, models.size());
        assertEquals(
                List.of("whisper-tiny-en-v1", "all-mpnet-base-v2-e8c3b32", "bge-reranker-v1"),
                models.stream().map(ModelCatalogEntry::id).toList());
    }

    @Test
    @DisplayName("a model resolves by id or by alias, ignoring case and surrounding space")
    void findModelResolvesByIdAndAlias() {
        assertEquals(
                "whisper-tiny-en-v1",
                ModelInstaller.findModel("whisper").orElseThrow().id());
        assertEquals(
                "whisper-tiny-en-v1",
                ModelInstaller.findModel("STT").orElseThrow().id());
        assertEquals(
                "all-mpnet-base-v2-e8c3b32",
                ModelInstaller.findModel("  MPNET  ").orElseThrow().id());
        assertEquals(
                "bge-reranker-v1",
                ModelInstaller.findModel("cross-encoder").orElseThrow().id());
        assertEquals(
                "bge-reranker-v1",
                ModelInstaller.findModel("bge-reranker-v1").orElseThrow().id());
    }

    @Test
    @DisplayName("an unknown, null or blank query resolves to nothing")
    void findModelRejectsUnknownQueries() {
        assertTrue(ModelInstaller.findModel(null).isEmpty());
        assertTrue(ModelInstaller.findModel("   ").isEmpty());
        assertTrue(ModelInstaller.findModel("nope").isEmpty());
    }

    @Test
    @DisplayName("an entry's own matcher rejects null and blank queries")
    void entryMatcherRejectsNullAndBlank() {
        ModelCatalogEntry entry = ModelInstaller.allModels().getFirst();

        assertFalse(entry.matches(null));
        assertFalse(entry.matches("  "));
        assertTrue(entry.matches("Whisper-Tiny"));
    }

    // ---------- model root resolution ----------

    @Test
    @DisabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")
    @DisplayName("the model root comes from the model-root property when it names a directory")
    void modelRootUsesTheModelRootProperty() {
        System.setProperty(MODEL_ROOT_PROPERTY, modelRoot.toString());

        assertEquals(modelRoot.toAbsolutePath().normalize(), ModelInstaller.resolveModelRoot());
    }

    @Test
    @DisabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")
    @DisplayName("a model-root that does not exist is returned as configured, not silently replaced")
    void modelRootReturnsAnAbsentConfiguredPath() {
        Path absent = modelRoot.resolve("definitely-absent");
        System.setProperty(MODEL_ROOT_PROPERTY, absent.toString());

        assertEquals(absent.toAbsolutePath().normalize(), ModelInstaller.resolveModelRoot());
    }

    @Test
    @DisabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")
    @DisplayName("the model root falls back to <data-dir>/models")
    void modelRootUsesTheDataDirProperty() throws IOException {
        Path models = Files.createDirectories(modelRoot.resolve("models"));
        System.setProperty(DATA_DIR_PROPERTY, modelRoot.toString());

        assertEquals(models.toAbsolutePath().normalize(), ModelInstaller.resolveModelRoot());
    }

    @Test
    @DisabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")
    @DisplayName("with no configuration the model root defaults to a data/models path")
    void modelRootDefaultsToDataModels() {
        Path resolved = ModelInstaller.resolveModelRoot();

        assertNotNull(resolved);
        assertTrue(
                resolved.toString().replace('\\', '/').endsWith("data/models"),
                "unexpected default model root: " + resolved);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = MODEL_ROOT_ENV, matches = ".+")
    @DisplayName("the model-root system property now outranks AMSHA_ONNX_MODEL_ROOT")
    void modelRootPropertyOutranksTheEnvironmentVariable() {
        // FIXED: resolveModelRoot() used to read the ENVIRONMENT first and the property second for the
        // model root, while reading AMSHA_DATA_DIR property-first — one method disagreeing with itself,
        // and with the runtime registry. Both sides now delegate to OnnxModelRootResolver, whose
        // precedence is property-before-environment, matching Quarkus/MicroProfile ordinals.
        Path fromProperty = modelRoot.resolve("wins-over-the-environment");
        System.setProperty(MODEL_ROOT_PROPERTY, fromProperty.toString());

        assertEquals(fromProperty.toAbsolutePath().normalize(), ModelInstaller.resolveModelRoot());
    }

    // ---------- status ----------

    @Test
    @DisplayName("a bundle with no directory at all is MISSING")
    void statusIsMissingWithoutADirectory() {
        assertEquals(ModelStatus.MISSING, ModelInstaller.checkStatus(entryFor("/ok.bin", PAYLOAD), modelRoot));
    }

    @Test
    @DisplayName("a bundle directory without a manifest is INCOMPLETE")
    void statusIsIncompleteWithoutAManifest() throws IOException {
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        Files.createDirectories(modelRoot.resolve(entry.id()));

        assertEquals(ModelStatus.INCOMPLETE, ModelInstaller.checkStatus(entry, modelRoot));
    }

    @Test
    @DisplayName("a bundle missing one of its files is INCOMPLETE")
    void statusIsIncompleteWhenAFileIsMissing() throws IOException {
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        Path bundleDir = modelRoot.resolve(entry.id());
        Files.createDirectories(bundleDir);
        Files.writeString(bundleDir.resolve("manifest.json"), entry.manifestJson());

        assertEquals(ModelStatus.INCOMPLETE, ModelInstaller.checkStatus(entry, modelRoot));
    }

    @Test
    @DisplayName("a file of the wrong size is INCOMPLETE")
    void statusIsIncompleteOnASizeMismatch() throws IOException {
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, "too short".getBytes(StandardCharsets.UTF_8));

        assertEquals(ModelStatus.INCOMPLETE, ModelInstaller.checkStatus(entry, modelRoot));
    }

    @Test
    @DisplayName("a complete bundle is INSTALLED")
    void statusIsInstalledWhenEverythingMatches() throws IOException {
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, PAYLOAD);

        assertEquals(ModelStatus.INSTALLED, ModelInstaller.checkStatus(entry, modelRoot));
    }

    // ---------- verification ----------

    @Test
    @DisplayName("verification fails when the bundle directory is absent")
    void verifyFailsWithoutADirectory() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);

        assertFalse(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Bundle directory missing"));
    }

    @Test
    @DisplayName("verification fails on a missing file")
    void verifyFailsOnAMissingFile() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        Files.createDirectories(modelRoot.resolve(entry.id()));

        assertFalse(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("File missing"), out);
        assertTrue(out.contains("manifest.json missing"), out);
    }

    @Test
    @DisplayName("verification fails on a size mismatch")
    void verifyFailsOnASizeMismatch() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, "short".getBytes(StandardCharsets.UTF_8));

        assertFalse(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("size mismatch"));
    }

    @Test
    @DisplayName("verification fails on a checksum mismatch")
    void verifyFailsOnAChecksumMismatch() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, new byte[PAYLOAD.length]); // right size, wrong bytes

        assertFalse(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("checksum mismatch"));
    }

    @Test
    @DisplayName("verification fails when the manifest is missing even though every file is valid")
    void verifyFailsWithoutAManifest() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        Path bundleDir = modelRoot.resolve(entry.id());
        Files.createDirectories(bundleDir);
        Files.write(bundleDir.resolve(FILE_NAME), PAYLOAD);

        assertFalse(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("manifest.json missing"));
    }

    @Test
    @DisplayName("verification succeeds and reports the digest prefix")
    void verifySucceedsOnAValidBundle() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, PAYLOAD);

        assertTrue(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("verified   " + FILE_NAME), out);
        assertTrue(out.contains("verified   manifest.json"), out);
        assertTrue(out.contains(sha256Hex(PAYLOAD).substring(0, 16)), out);
    }

    @Test
    @DisplayName("an unreadable file reports an I/O error rather than throwing")
    void verifyReportsAnIoErrorForAnUnreadableFile() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, PAYLOAD);
        Path file = modelRoot.resolve(entry.id()).resolve(FILE_NAME);
        // Size still reads fine, so the failure must come from opening the stream for hashing.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assertFalse(ModelInstaller.verifyBundle(entry, modelRoot, sink(captured)));
            assertTrue(captured.toString(StandardCharsets.UTF_8).contains("I/O error"));
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @DisplayName("an existing file that cannot be read is re-downloaded")
    void installRedownloadsAFileItCannotRead() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, PAYLOAD);
        Path file = modelRoot.resolve(entry.id()).resolve(FILE_NAME);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            ModelInstaller.installBundle(entry, modelRoot, sink(captured));
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }

        assertEquals(1, requests.get(), "an unreadable file must be re-fetched, not trusted");
        assertEquals(sha256Hex(PAYLOAD), ModelInstaller.computeSha256(file));
    }

    // ---------- install: no network needed ----------

    @Test
    @DisplayName("installing over already-valid files downloads nothing")
    void installReusesValidFilesWithoutDownloading() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, PAYLOAD);
        Files.delete(modelRoot.resolve(entry.id()).resolve("manifest.json")); // force the manifest rewrite

        ModelInstaller.installBundle(entry, modelRoot, sink(captured));

        assertEquals(0, requests.get(), "a valid file must be reused, not re-downloaded");
        assertEquals(ModelStatus.INSTALLED, ModelInstaller.checkStatus(entry, modelRoot));
        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("successfully installed"), out);
    }

    @Test
    @DisplayName("a corrupt existing file is re-downloaded and replaced")
    void installReplacesACorruptExistingFile() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        writeValidBundle(entry, "corrupt".getBytes(StandardCharsets.UTF_8));

        ModelInstaller.installBundle(entry, modelRoot, sink(captured));

        assertEquals(1, requests.get());
        assertEquals(PAYLOAD.length, Files.size(modelRoot.resolve(entry.id()).resolve(FILE_NAME)));
        assertEquals(ModelStatus.INSTALLED, ModelInstaller.checkStatus(entry, modelRoot));
    }

    @Test
    @DisplayName("a directory in place of the target file fails the install and leaves no partial behind")
    void installFailsWhenADirectoryOccupiesTheTargetPath() throws IOException {
        // FINDING (reported, not "fixed"): installFile's "re-download a corrupt file" path cannot replace a
        // DIRECTORY — Files.move(..., REPLACE_EXISTING) raises "Is a directory". The failure is clean (the
        // partial file is removed and the IOException propagates), but a stray directory at the target path
        // blocks the install rather than being replaced.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);
        Path bundleDir = modelRoot.resolve(entry.id());
        Files.createDirectories(bundleDir.resolve(FILE_NAME)); // directory where a file belongs

        assertThrows(IOException.class, () -> ModelInstaller.installBundle(entry, modelRoot, sink(captured)));

        assertFalse(Files.exists(bundleDir.resolve(FILE_NAME + ".partial")), "no partial file may survive");
        assertEquals(ModelStatus.INCOMPLETE, ModelInstaller.checkStatus(entry, modelRoot));
    }

    // ---------- install: real HTTP boundary ----------

    @Test
    @DisplayName("a fresh install downloads, verifies and publishes the bundle")
    void installDownloadsVerifiesAndPublishes() throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/ok.bin", PAYLOAD);

        ModelInstaller.installBundle(entry, modelRoot, sink(captured));

        assertEquals(1, requests.get());
        Path bundleDir = modelRoot.resolve(entry.id());
        assertEquals(PAYLOAD.length, Files.size(bundleDir.resolve(FILE_NAME)));
        assertTrue(Files.exists(bundleDir.resolve("manifest.json")));
        assertFalse(Files.exists(bundleDir.resolve(FILE_NAME + ".partial")), "no partial file may survive");
        assertEquals(sha256Hex(PAYLOAD), ModelInstaller.computeSha256(bundleDir.resolve(FILE_NAME)));
        assertEquals(ModelStatus.INSTALLED, ModelInstaller.checkStatus(entry, modelRoot));
    }

    @Test
    @DisplayName("a non-200 response fails the install and leaves no partial file")
    void installRejectsANonOkStatus() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/missing.bin", PAYLOAD);

        IOException ex =
                assertThrows(IOException.class, () -> ModelInstaller.installBundle(entry, modelRoot, sink(captured)));

        assertTrue(ex.getMessage().contains("status 404"), ex.getMessage());
        assertFalse(Files.exists(modelRoot.resolve(entry.id()).resolve(FILE_NAME + ".partial")));
    }

    @Test
    @DisplayName("a short download fails the install")
    void installRejectsAShortDownload() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/wrong-size.bin", PAYLOAD);

        IOException ex =
                assertThrows(IOException.class, () -> ModelInstaller.installBundle(entry, modelRoot, sink(captured)));

        assertTrue(ex.getMessage().contains("size mismatch"), ex.getMessage());
    }

    @Test
    @DisplayName("a download whose checksum does not match fails the install")
    void installRejectsAChecksumMismatch() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/wrong-hash.bin", PAYLOAD);

        IOException ex =
                assertThrows(IOException.class, () -> ModelInstaller.installBundle(entry, modelRoot, sink(captured)));

        assertTrue(ex.getMessage().contains("checksum mismatch"), ex.getMessage());
    }

    @Test
    @DisplayName("an interrupt during the download fails the install and leaves no partial file")
    void installFailsWhenTheDownloadIsInterrupted() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ModelCatalogEntry entry = entryFor("/slow.bin", PAYLOAD);

        try {
            IOException ex = assertThrows(IOException.class, () -> {
                // Pre-setting the flag makes HttpClient.send raise InterruptedException instead of waiting out
                // the server's 3 s delay; /slow.bin guarantees the request is genuinely still in flight.
                Thread.currentThread().interrupt();
                ModelInstaller.installBundle(entry, modelRoot, sink(captured));
            });

            assertTrue(ex.getMessage().contains("Download interrupted"), ex.getMessage());
            assertFalse(
                    Files.exists(modelRoot.resolve(entry.id()).resolve(FILE_NAME + ".partial")),
                    "no partial file may survive an interrupted download");
        } finally {
            Thread.interrupted(); // clear the flag so it cannot leak into the next test
        }
    }

    // ---------- primitives ----------

    @Test
    @DisplayName("computeSha256 matches the standard test vector")
    void computeSha256MatchesTheKnownVector() throws IOException {
        Path file = modelRoot.resolve("abc.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelInstaller.computeSha256(file));
    }

    @Test
    @DisplayName("computeSha256 streams a file larger than its read buffer")
    void computeSha256HandlesMultipleBufferReads() throws IOException {
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i % 251);
        }
        Path file = modelRoot.resolve("big.bin");
        Files.write(file, big);

        assertEquals(sha256Hex(big), ModelInstaller.computeSha256(file));
    }

    @Test
    @DisplayName("formatBytes covers all four magnitudes")
    void formatBytesCoversAllMagnitudes() {
        assertEquals("0 B", ModelInstaller.formatBytes(0));
        assertEquals("1023 B", ModelInstaller.formatBytes(1023));
        assertEquals("1.0 KB", ModelInstaller.formatBytes(1024));
        assertEquals("1.0 MB", ModelInstaller.formatBytes(1024L * 1024));
        assertEquals("1.00 GB", ModelInstaller.formatBytes(1024L * 1024 * 1024));
    }

    @Test
    @DisplayName("the catalog's real entries agree with their own manifests")
    void catalogEntriesAreInternallyConsistent() {
        for (ModelCatalogEntry entry : ModelInstaller.allModels()) {
            assertNotNull(entry.manifestJson());
            assertFalse(entry.files().isEmpty(), entry.id() + " must declare at least one file");
            for (ModelFileSpec file : entry.files()) {
                assertTrue(file.expectedBytes() > 0, file.fileName());
                assertEquals(64, file.expectedSha256().length(), file.fileName());
                assertTrue(file.remoteUrl().startsWith("https://"), file.remoteUrl());
            }
        }
    }

    @Test
    @DisplayName("an empty catalog query never matches anything")
    void emptyQueryNeverMatches() {
        Optional<ModelCatalogEntry> none = ModelInstaller.findModel("");
        assertTrue(none.isEmpty());
    }
}
