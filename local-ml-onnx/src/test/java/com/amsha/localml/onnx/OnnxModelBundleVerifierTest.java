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

class OnnxModelBundleVerifierTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void loadsVerifiedBundle() throws Exception {
        Path bundle = writeBundle("model-contents", "tokenizer-contents");
        VerifiedOnnxModelBundle verified = new OnnxModelBundleVerifier(objectMapper, 1024).loadAndVerify(bundle);
        assertEquals("test-bundle", verified.manifest().bundleId());
        assertEquals(bundle.resolve("model.onnx").toRealPath(), verified.modelPath());
    }

    @Test
    void rejectsTamperedBundle() throws Exception {
        Path bundle = writeBundle("model-contents", "tokenizer-contents");
        Files.writeString(bundle.resolve("model.onnx"), "tampered-model");
        assertThrows(IOException.class, () -> new OnnxModelBundleVerifier(objectMapper, 1024).loadAndVerify(bundle));
    }

    @Test
    void rejectsTraversalInManifest() {
        String digest = "0".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxModelBundleManifest(
                        "test-bundle", "revision-1", "../model.onnx", digest, 1, "tokenizer.json", digest, 1));
    }

    @Test
    void rejectsModelAndTokenizerUsingTheSameFile() {
        String digest = "0".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxModelBundleManifest(
                        "test-bundle", "revision-1", "model.onnx", digest, 1, "model.onnx", digest, 1));
    }

    @Test
    void acceptsFutureManifestMetadataWithoutWeakeningRequiredFieldValidation() throws Exception {
        OnnxModelBundleManifest manifest = objectMapper.readValue("""
                {"bundleId":"test-bundle","revision":"revision-1","modelFile":"model.onnx",
                "modelSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "modelBytes":1,"tokenizerFile":"tokenizer.json",
                "tokenizerSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "tokenizerBytes":1,"chunkingStrategy":"FIRST_MAX_TOKENS"}
                """, OnnxModelBundleManifest.class);

        assertEquals("test-bundle", manifest.bundleId());
    }

    @Test
    void readsValidatedEmbeddingContractFromManifest() throws Exception {
        OnnxModelBundleManifest manifest = objectMapper.readValue("""
                {"bundleId":"test-bundle","revision":"revision-1","modelFile":"model.onnx",
                "modelSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "modelBytes":1,"tokenizerFile":"tokenizer.json",
                "tokenizerSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "tokenizerBytes":1,"embedding":{"tokenizerRevision":"v1","inputIdsName":"input_ids",
                "attentionMaskName":"attention_mask","outputName":"last_hidden_state",
                "maxSequenceLength":512,"vectorDimension":768,"poolingStrategy":"MASKED_MEAN",
                "l2Normalize":true,"spaceId":"sentence-transformer/example","spaceRevision":"v1"}}
                """, OnnxModelBundleManifest.class);

        OnnxEmbeddingBundleSpec embedding = manifest.embeddingSpec().orElseThrow();
        assertEquals("last_hidden_state", embedding.outputName());
        assertEquals(OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN, embedding.poolingStrategy());
    }

    @Test
    void rejectsDuplicateEmbeddingTensorNames() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxEmbeddingBundleSpec(
                        "v1",
                        "input_ids",
                        "input_ids",
                        null,
                        "last_hidden_state",
                        512,
                        768,
                        OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN,
                        true,
                        "sentence-transformer/example",
                        "v1"));
    }

    private Path writeBundle(String modelContents, String tokenizerContents) throws Exception {
        Path bundle = Files.createDirectory(tempDir.resolve("test-bundle"));
        Path model = bundle.resolve("model.onnx");
        Path tokenizer = bundle.resolve("tokenizer.json");
        Files.writeString(model, modelContents);
        Files.writeString(tokenizer, tokenizerContents);
        OnnxModelBundleManifest manifest = new OnnxModelBundleManifest(
                "test-bundle",
                "revision-1",
                "model.onnx",
                sha256(model),
                Files.size(model),
                "tokenizer.json",
                sha256(tokenizer),
                Files.size(tokenizer));
        objectMapper.writeValue(bundle.resolve("manifest.json").toFile(), manifest);
        return bundle;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
