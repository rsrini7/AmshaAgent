package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnnxModelBundleRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void loadsOnlyAConfiguredRootChild() throws Exception {
        Path bundle = writeBundle(tempDir.resolve("bundle-one"));
        OnnxModelBundleRegistry registry = new OnnxModelBundleRegistry(objectMapper, true, tempDir, 1024);

        assertEquals(
                "bundle-one", registry.loadVerified("bundle-one").manifest().bundleId());
        assertEquals(
                bundle.resolve("model.onnx").toRealPath(),
                registry.loadVerified("bundle-one").modelPath());
    }

    @Test
    void rejectsInvalidBundleIdBeforeFilesystemLookup() {
        OnnxModelBundleRegistry registry = new OnnxModelBundleRegistry(objectMapper, true, tempDir, 1024);

        assertThrows(IOException.class, () -> registry.loadVerified("../outside"));
    }

    @Test
    void rejectsDisabledFeature() {
        OnnxModelBundleRegistry registry = new OnnxModelBundleRegistry(objectMapper, false, tempDir, 1024);

        assertThrows(IOException.class, () -> registry.loadVerified("bundle-one"));
    }

    @Test
    void rejectsManifestWhoseIdentityDiffersFromTheSelectedBundle() throws Exception {
        Path bundle = writeBundle(tempDir.resolve("bundle-one"));
        Path manifest = bundle.resolve("manifest.json");
        OnnxModelBundleManifest original = objectMapper.readValue(manifest.toFile(), OnnxModelBundleManifest.class);
        objectMapper.writeValue(
                manifest.toFile(),
                new OnnxModelBundleManifest(
                        "different-bundle",
                        original.revision(),
                        original.modelFile(),
                        original.modelSha256(),
                        original.modelBytes(),
                        original.tokenizerFile(),
                        original.tokenizerSha256(),
                        original.tokenizerBytes()));
        OnnxModelBundleRegistry registry = new OnnxModelBundleRegistry(objectMapper, true, tempDir, 1024);

        assertThrows(IOException.class, () -> registry.loadVerified("bundle-one"));
    }

    private Path writeBundle(Path bundle) throws Exception {
        Files.createDirectory(bundle);
        Path model = bundle.resolve("model.onnx");
        Path tokenizer = bundle.resolve("tokenizer.json");
        Files.writeString(model, "model-contents");
        Files.writeString(tokenizer, "tokenizer-contents");
        objectMapper.writeValue(
                bundle.resolve("manifest.json").toFile(),
                new OnnxModelBundleManifest(
                        "bundle-one",
                        "revision-1",
                        "model.onnx",
                        sha256(model),
                        Files.size(model),
                        "tokenizer.json",
                        sha256(tokenizer),
                        Files.size(tokenizer)));
        return bundle;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
