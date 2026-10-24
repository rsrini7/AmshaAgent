package com.amsha.app.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.localml.onnx.OnnxModelBundleIds;
import com.amsha.localml.onnx.OnnxModelInstallGuidance;
import com.amsha.localml.onnx.OnnxModelRootResolver;

/**
 * Downloads, verifies, and installs pinned offline ONNX model bundles for Amsha Agent.
 * Ensures strict cryptographic SHA-256 and byte-count integrity with atomic file replacement.
 */
public final class ModelInstaller {

    public static final String SELECTOR_ALL = "all";
    public static final String SELECTOR_WHISPER = "whisper";
    public static final String SELECTOR_MPNET = "mpnet";
    public static final String SELECTOR_RERANKER = "bge-reranker";
    public static final String JVM_LAUNCHER_COMMAND = OnnxModelInstallGuidance.DEVELOPER_JAR_LAUNCHER;

    public static final String WHISPER_BUNDLE_ID = OnnxModelBundleIds.WHISPER;
    public static final String MPNET_BUNDLE_ID = OnnxModelBundleIds.MPNET;
    public static final String RERANKER_BUNDLE_ID = OnnxModelBundleIds.RERANKER;
    public static final String MANIFEST_FILE_NAME = OnnxModelBundleIds.MANIFEST_FILE_NAME;

    private static final int DOWNLOAD_PROGRESS_INTERVAL_PERCENT = 25;

    public record ModelFileSpec(String fileName, String remoteUrl, long expectedBytes, String expectedSha256) {}

    public record ModelCatalogEntry(
            String id,
            List<String> aliases,
            String description,
            String totalSize,
            List<ModelFileSpec> files,
            String manifestJson) {
        public boolean matches(String query) {
            if (query == null || query.isBlank()) return false;
            String normalized = query.trim().toLowerCase(Locale.ROOT);
            if (id.toLowerCase(Locale.ROOT).equals(normalized)) return true;
            return aliases.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).equals(normalized));
        }
    }

    public enum ModelStatus {
        INSTALLED,
        INCOMPLETE,
        MISSING
    }

    private static final String HF_WHISPER_BASE =
            "https://huggingface.co/Xenova/whisper-tiny.en/resolve/79fb389fc764e7c395bd330e9531d9d32ada7049/";
    private static final String HF_MPNET_BASE =
            "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/e8c3b32edf5434bc2275fc9bab85f82640a19130/";
    private static final String HF_RERANKER_BASE = "https://huggingface.co/Xenova/bge-reranker-base/resolve/main/";

    private static final String WHISPER_MANIFEST = """
            {
              "bundleId": "whisper-tiny-en-v1",
              "revision": "79fb389fc764e7c395bd330e9531d9d32ada7049",
              "modelFile": "encoder_model.onnx",
              "modelSha256": "8cc3c6f8563d1b3fbd2c5af9f64c2bed8b020bc593c402d1ef53b9f08fbf1b90",
              "modelBytes": 10124913,
              "tokenizerFile": "tokenizer.json",
              "tokenizerSha256": "c6ee8f089220a5b1188f6426456772572671c6141ae007eecb83c6a8349f5deb",
              "tokenizerBytes": 2128494,
              "estimatedResidentBytes": 128146998,
              "audioTranscription": {
                "decoderFile": "decoder_model.onnx",
                "decoderSha256": "62b1761a6c23961cdabbf7caa25495525d78528a64baacf7e4283c83cb2941f1",
                "decoderBytes": 30459718,
                "preprocessorConfigFile": "preprocessor_config.json",
                "preprocessorConfigSha256": "a6a76d28c93edb273669eb9e0b0636a2bddbb1272c3261e47b7ca6dfdbac1b8d",
                "configFile": "config.json",
                "configSha256": "37a1073be00d19118c06557896c7c148598f4d8277edc0f5bc07c9f5554839f1",
                "sampleRate": 16000,
                "numChannels": 1,
                "numMelBins": 80,
                "windowLength": 400,
                "hopLength": 160,
                "maxAudioDurationSeconds": 30,
                "maxSequenceLength": 448,
                "suppressBlank": true
              },
              "source": {
                "repository": "https://huggingface.co/Xenova/whisper-tiny.en",
                "revision": "79fb389fc764e7c395bd330e9531d9d32ada7049",
                "modelPath": "onnx/encoder_model_quantized.onnx",
                "tokenizerPath": "tokenizer.json",
                "decoderPath": "onnx/decoder_model_quantized.onnx",
                "license": "Apache-2.0"
              }
            }
            """;

    private static final String MPNET_MANIFEST = """
            {
              "bundleId": "all-mpnet-base-v2-e8c3b32",
              "revision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
              "modelFile": "model.onnx",
              "modelSha256": "74187b16d9c946fea252e120cfd7a12c5779d8b8b86838a2e4c56573c47941bd",
              "modelBytes": 435826548,
              "tokenizerFile": "tokenizer.json",
              "tokenizerSha256": "b8be2c30ba5dd723a6d5ee26d013da103d5408d92ddcb23747622f9e48f1d842",
              "tokenizerBytes": 466021,
              "embedding": {
                "tokenizerRevision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
                "inputIdsName": "input_ids",
                "attentionMaskName": "attention_mask",
                "tokenTypeIdsName": null,
                "outputName": "last_hidden_state",
                "maxSequenceLength": 384,
                "vectorDimension": 768,
                "paddingTokenId": 1,
                "poolingStrategy": "MASKED_MEAN",
                "l2Normalize": true,
                "spaceId": "sentence-transformers/all-mpnet-base-v2",
                "spaceRevision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
                "warmupProbes": [
                  "Amsha local ONNX warm-up.",
                  "Vector embedding readiness probe."
                ]
              },
              "source": {
                "repository": "https://huggingface.co/sentence-transformers/all-mpnet-base-v2",
                "revision": "e8c3b32edf5434bc2275fc9bab85f82640a19130",
                "modelPath": "onnx/model.onnx",
                "tokenizerPath": "tokenizer.json",
                "license": "Apache-2.0"
              }
            }
            """;

    private static final String RERANKER_MANIFEST = """
            {
              "bundleId": "bge-reranker-v1",
              "revision": "a76e9c60e58c8e1a1005a76e9389270e5b9fbb40",
              "modelFile": "model.onnx",
              "modelSha256": "15b9a8c3da82eddf263df571281166e00e9308fe19d077084b642ebfcaf06d2b",
              "modelBytes": 1112459588,
              "tokenizerFile": "tokenizer.json",
              "tokenizerSha256": "48564c5c7d3fa64d85d95e65414a542385f88b0f128fd8d4163fd7a57f2be05c",
              "tokenizerBytes": 17098079,
              "scoring": {
                "inputType": "text-pair",
                "tokenizerRevision": "a76e9c60e58c8e1a1005a76e9389270e5b9fbb40",
                "inputIdsName": "input_ids",
                "attentionMaskName": "attention_mask",
                "tokenTypeIdsName": null,
                "outputName": "logits",
                "maxSequenceLength": 384,
                "paddingTokenId": 1,
                "outputLabel": "relevance",
                "scoreDirection": "HIGHER_IS_BETTER",
                "expectedOutputShape": "BATCH_BY_ONE",
                "warmupProbes": [
                  "Amsha local ONNX reranker warm-up probe query.",
                  "Candidate document text for cross-encoder readiness validation."
                ]
              },
              "source": {
                "repository": "https://huggingface.co/Xenova/bge-reranker-base",
                "revision": "a76e9c60e58c8e1a1005a76e9389270e5b9fbb40",
                "modelPath": "onnx/model.onnx",
                "tokenizerPath": "tokenizer.json",
                "license": "Apache-2.0"
              }
            }
            """;

    private static final ModelCatalogEntry WHISPER_ENTRY = new ModelCatalogEntry(
            WHISPER_BUNDLE_ID,
            List.of(SELECTOR_WHISPER, "whisper-tiny", "stt"),
            "Local offline Whisper audio transcription",
            "~43 MB",
            List.of(
                    new ModelFileSpec(
                            "encoder_model.onnx",
                            HF_WHISPER_BASE + "onnx/encoder_model_quantized.onnx",
                            10124913L,
                            "8cc3c6f8563d1b3fbd2c5af9f64c2bed8b020bc593c402d1ef53b9f08fbf1b90"),
                    new ModelFileSpec(
                            "decoder_model.onnx",
                            HF_WHISPER_BASE + "onnx/decoder_model_quantized.onnx",
                            30459718L,
                            "62b1761a6c23961cdabbf7caa25495525d78528a64baacf7e4283c83cb2941f1"),
                    new ModelFileSpec(
                            "tokenizer.json",
                            HF_WHISPER_BASE + "tokenizer.json",
                            2128494L,
                            "c6ee8f089220a5b1188f6426456772572671c6141ae007eecb83c6a8349f5deb"),
                    new ModelFileSpec(
                            "preprocessor_config.json",
                            HF_WHISPER_BASE + "preprocessor_config.json",
                            339L,
                            "a6a76d28c93edb273669eb9e0b0636a2bddbb1272c3261e47b7ca6dfdbac1b8d"),
                    new ModelFileSpec(
                            "config.json",
                            HF_WHISPER_BASE + "config.json",
                            2202L,
                            "37a1073be00d19118c06557896c7c148598f4d8277edc0f5bc07c9f5554839f1")),
            WHISPER_MANIFEST);

    private static final ModelCatalogEntry MPNET_ENTRY = new ModelCatalogEntry(
            MPNET_BUNDLE_ID,
            List.of(SELECTOR_MPNET, "embedding", "embeddings"),
            "Local dense vector embeddings",
            "~436 MB",
            List.of(
                    new ModelFileSpec(
                            "model.onnx",
                            HF_MPNET_BASE + "onnx/model.onnx",
                            435826548L,
                            "74187b16d9c946fea252e120cfd7a12c5779d8b8b86838a2e4c56573c47941bd"),
                    new ModelFileSpec(
                            "tokenizer.json",
                            HF_MPNET_BASE + "tokenizer.json",
                            466021L,
                            "b8be2c30ba5dd723a6d5ee26d013da103d5408d92ddcb23747622f9e48f1d842")),
            MPNET_MANIFEST);

    private static final ModelCatalogEntry RERANKER_ENTRY = new ModelCatalogEntry(
            RERANKER_BUNDLE_ID,
            List.of("reranker", SELECTOR_RERANKER, "cross-encoder"),
            "Local cross-encoder candidate reranker",
            "~1.1 GB",
            List.of(
                    new ModelFileSpec(
                            "model.onnx",
                            HF_RERANKER_BASE + "onnx/model_quantized.onnx",
                            1112459588L,
                            "15b9a8c3da82eddf263df571281166e00e9308fe19d077084b642ebfcaf06d2b"),
                    new ModelFileSpec(
                            "tokenizer.json",
                            HF_RERANKER_BASE + "tokenizer.json",
                            17098079L,
                            "48564c5c7d3fa64d85d95e65414a542385f88b0f128fd8d4163fd7a57f2be05c")),
            RERANKER_MANIFEST);

    private static final List<ModelCatalogEntry> CATALOG = List.of(WHISPER_ENTRY, MPNET_ENTRY, RERANKER_ENTRY);

    private ModelInstaller() {}

    public static List<ModelCatalogEntry> allModels() {
        return CATALOG;
    }

    public static List<String> launcherCommands() {
        return ModelLauncherCommands.launcherCommands();
    }

    public static String launcherCommand() {
        return ModelLauncherCommands.launcherCommand();
    }

    public static Optional<ModelCatalogEntry> findModel(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        return CATALOG.stream().filter(entry -> entry.matches(query)).findFirst();
    }

    /**
     * Resolves the model root through the canonical rule set shared with the runtime registry.
     *
     * <p>This previously kept its own chain, which probed a parent directory for a relative root but
     * the runtime did not. An install launched from {@code dist/} therefore succeeded while the
     * running process looked somewhere else. Both sides now delegate to
     * {@link com.amsha.localml.onnx.OnnxModelRootResolver}, which also makes precedence consistent:
     * the system property outranks the environment variable, as it does in Quarkus.
     *
     * @return an absolute, normalized model root; never {@code null}
     */
    public static Path resolveModelRoot() {
        return OnnxModelRootResolver.resolve();
    }

    public static ModelStatus checkStatus(ModelCatalogEntry entry, Path modelRoot) {
        Path bundleDir = modelRoot.resolve(entry.id());
        if (!Files.isDirectory(bundleDir)) {
            return ModelStatus.MISSING;
        }
        Path manifest = bundleDir.resolve(MANIFEST_FILE_NAME);
        if (!Files.exists(manifest)) {
            return ModelStatus.INCOMPLETE;
        }
        for (ModelFileSpec file : entry.files()) {
            Path target = bundleDir.resolve(file.fileName());
            if (!Files.exists(target)) {
                return ModelStatus.INCOMPLETE;
            }
            try {
                if (Files.size(target) != file.expectedBytes()) {
                    return ModelStatus.INCOMPLETE;
                }
            } catch (IOException _) {
                return ModelStatus.INCOMPLETE;
            }
        }
        return ModelStatus.INSTALLED;
    }

    public static boolean verifyBundle(ModelCatalogEntry entry, Path modelRoot, PrintStream out) {
        Path bundleDir = modelRoot.resolve(entry.id());
        if (!Files.isDirectory(bundleDir)) {
            out.printf("  ❌ Bundle directory missing: %s%n", bundleDir);
            return false;
        }
        boolean allValid = true;
        for (ModelFileSpec file : entry.files()) {
            Path target = bundleDir.resolve(file.fileName());
            if (!Files.exists(target)) {
                out.printf("  ❌ File missing: %s%n", file.fileName());
                allValid = false;
                continue;
            }
            try {
                long size = Files.size(target);
                if (size != file.expectedBytes()) {
                    out.printf(
                            "  ❌ %s: size mismatch (found %d, expected %d)%n",
                            file.fileName(), size, file.expectedBytes());
                    allValid = false;
                    continue;
                }
                String hash = computeSha256(target);
                if (!hash.equalsIgnoreCase(file.expectedSha256())) {
                    out.printf("  ❌ %s: checksum mismatch%n", file.fileName());
                    allValid = false;
                    continue;
                }
                out.printf(
                        "  verified   %s (%s, sha: %s...)%n",
                        file.fileName(), formatBytes(size), hash.substring(0, 16));
            } catch (IOException e) {
                out.printf("  ❌ %s: I/O error: %s%n", file.fileName(), e.getMessage());
                allValid = false;
            }
        }
        Path manifest = bundleDir.resolve(MANIFEST_FILE_NAME);
        if (Files.exists(manifest)) {
            out.println("  verified   manifest.json");
        } else {
            out.println("  ❌ manifest.json missing");
            allValid = false;
        }
        return allValid;
    }

    public static void installBundle(ModelCatalogEntry entry, Path modelRoot, PrintStream out) throws IOException {
        Path bundleDir = modelRoot.resolve(entry.id());
        Files.createDirectories(bundleDir);
        out.printf("Installing '%s' into %s...%n", entry.id(), bundleDir);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        for (ModelFileSpec file : entry.files()) {
            installFile(file, bundleDir, client, out);
        }

        Path manifest = bundleDir.resolve(MANIFEST_FILE_NAME);
        Files.writeString(
                manifest,
                entry.manifestJson(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        out.println("  verified   manifest.json");
        out.printf("✅ Local model bundle '%s' successfully installed and verified.%n", entry.id());
    }

    private static void installFile(ModelFileSpec file, Path bundleDir, HttpClient client, PrintStream out)
            throws IOException {
        Path target = bundleDir.resolve(file.fileName());
        if (Files.exists(target)) {
            try {
                if (Files.size(target) == file.expectedBytes()
                        && computeSha256(target).equalsIgnoreCase(file.expectedSha256())) {
                    out.printf("  verified   %s (%s)%n", file.fileName(), formatBytes(file.expectedBytes()));
                    return;
                }
            } catch (IOException _) {
                // re-download corrupt file
            }
        }

        Path partial = bundleDir.resolve(file.fileName() + ".partial");
        Files.deleteIfExists(partial);
        out.printf("  Downloading %s (%s)...%n", file.fileName(), formatBytes(file.expectedBytes()));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(file.remoteUrl()))
                .header("User-Agent", "AmshaModelInstaller/1.0")
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                try (InputStream _ = resp.body()) {
                    // Close the response body before reporting the HTTP status.
                }
                throw new IOException("HTTP download failed: status " + resp.statusCode() + " for " + file.remoteUrl());
            }
            MessageDigest digest = createSha256Digest();
            long totalRead = 0;
            int nextProgressPercent = DOWNLOAD_PROGRESS_INTERVAL_PERCENT;
            try (InputStream in = resp.body();
                    OutputStream fos =
                            Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                    fos.write(buf, 0, read);
                    totalRead += read;
                    if (file.expectedBytes() > 0) {
                        int progressPercent = (int) (totalRead * 100.0 / file.expectedBytes());
                        if (progressPercent >= nextProgressPercent) {
                            out.printf(
                                    "  Downloaded %s of %s (%d%%)%n",
                                    formatBytes(Math.min(totalRead, file.expectedBytes())),
                                    formatBytes(file.expectedBytes()),
                                    Math.min(progressPercent, 100));
                            nextProgressPercent = (progressPercent / DOWNLOAD_PROGRESS_INTERVAL_PERCENT + 1)
                                    * DOWNLOAD_PROGRESS_INTERVAL_PERCENT;
                        }
                    }
                }
            }

            if (totalRead != file.expectedBytes()) {
                throw new IOException("Downloaded file size mismatch for " + file.fileName() + ": expected "
                        + file.expectedBytes() + " bytes, received " + totalRead);
            }

            String computed = HexFormat.of().formatHex(digest.digest());
            if (!computed.equalsIgnoreCase(file.expectedSha256())) {
                throw new IOException("SHA-256 checksum mismatch for " + file.fileName() + ": expected "
                        + file.expectedSha256() + ", got " + computed);
            }

            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            out.printf(
                    "  verified   %s (%s, sha: %s...)%n",
                    file.fileName(), formatBytes(totalRead), computed.substring(0, 16));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deletePartialWithoutMasking(partial, e);
            throw new IOException("Download interrupted for " + file.fileName(), e);
        } catch (IOException | RuntimeException e) {
            deletePartialWithoutMasking(partial, e);
            throw e;
        }
    }

    private static void deletePartialWithoutMasking(Path partial, Exception originalFailure) {
        try {
            Files.deleteIfExists(partial);
        } catch (IOException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    public static String computeSha256(Path file) throws IOException {
        MessageDigest digest = createSha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int read;
            while ((read = in.read(buf)) != -1) {
                digest.update(buf, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
