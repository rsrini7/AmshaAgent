package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

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
        assertEquals(
                2L * (Files.size(bundle.resolve("model.onnx")) + Files.size(bundle.resolve("tokenizer.json"))),
                verified.manifest().estimatedResidentBytes());
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
                        "test-bundle",
                        "revision-1",
                        "../model.onnx",
                        digest,
                        1,
                        "tokenizer.json",
                        digest,
                        1,
                        0,
                        null,
                        null,
                        source()));
    }

    @Test
    void rejectsModelAndTokenizerUsingTheSameFile() {
        String digest = "0".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxModelBundleManifest(
                        "test-bundle",
                        "revision-1",
                        "model.onnx",
                        digest,
                        1,
                        "model.onnx",
                        digest,
                        1,
                        0,
                        null,
                        null,
                        source()));
    }

    @Test
    void rejectsResidentEstimateBelowVerifiedBundleBytes() {
        String digest = "0".repeat(64);
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxModelBundleManifest(
                        "test-bundle",
                        "revision-1",
                        "model.onnx",
                        digest,
                        10,
                        "tokenizer.json",
                        digest,
                        5,
                        14,
                        null,
                        null,
                        source()));
    }

    @Test
    void acceptsFutureManifestMetadataWithoutWeakeningRequiredFieldValidation() throws Exception {
        OnnxModelBundleManifest manifest = objectMapper.readValue("""
                {"bundleId":"test-bundle","revision":"revision-1","modelFile":"model.onnx",
                "modelSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "modelBytes":1,"tokenizerFile":"tokenizer.json",
                "tokenizerSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "tokenizerBytes":1,"chunkingStrategy":"FIRST_MAX_TOKENS","source":{"repository":"https://example.test/model","revision":"revision-1","modelPath":"model.onnx","tokenizerPath":"tokenizer.json","license":"Apache-2.0"}}
                """, OnnxModelBundleManifest.class);

        assertEquals("test-bundle", manifest.bundleId());
    }

    @Test
    void rejectsManifestWithoutRequiredSourceProvenance() {
        assertThrows(IOException.class, () -> objectMapper.readValue("""
                        {"bundleId":"test-bundle","revision":"revision-1","modelFile":"model.onnx",
                        "modelSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                        "modelBytes":1,"tokenizerFile":"tokenizer.json",
                        "tokenizerSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                        "tokenizerBytes":1}
                        """, OnnxModelBundleManifest.class));
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
                "paddingTokenId":0,"l2Normalize":true,"spaceId":"sentence-transformer/example","spaceRevision":"v1",
                "warmupProbes":["warm-up"]},"source":{"repository":"https://example.test/model","revision":"revision-1","modelPath":"model.onnx","tokenizerPath":"tokenizer.json","license":"Apache-2.0"}}
                """, OnnxModelBundleManifest.class);

        OnnxEmbeddingBundleSpec embedding = manifest.embeddingSpec().orElseThrow();
        assertEquals("last_hidden_state", embedding.outputName());
        assertEquals(OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN, embedding.poolingStrategy());
        assertEquals(OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_STRATEGY, embedding.chunkingStrategy());
        assertEquals(512, embedding.maxWindowTokens());
    }

    @Test
    void readsValidatedClassifierContractFromManifest() throws Exception {
        OnnxModelBundleManifest manifest = objectMapper.readValue("""
                {"bundleId":"test-bundle","revision":"revision-1","modelFile":"model.onnx",
                "modelSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "modelBytes":1,"tokenizerFile":"tokenizer.json",
                "tokenizerSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "tokenizerBytes":1,"classifier":{"tokenizerRevision":"v1","inputIdsName":"input_ids",
                "attentionMaskName":"attention_mask","outputName":"logits","maxSequenceLength":128,
                "paddingTokenId":0,"labels":["coordinator","coder"],"scoreTransform":"SOFTMAX",
                "minimumConfidence":0.75,"ruleId":"intent-v1","warmupProbes":["warm-up"]},"source":{"repository":"https://example.test/model","revision":"revision-1","modelPath":"model.onnx","tokenizerPath":"tokenizer.json","license":"Apache-2.0"}}
                """, OnnxModelBundleManifest.class);

        OnnxClassifierBundleSpec classifier = manifest.classifierSpec().orElseThrow();
        assertEquals(List.of("coordinator", "coder"), classifier.labels());
        assertEquals(OnnxClassifierBundleSpec.ScoreTransform.SOFTMAX, classifier.scoreTransform());
    }

    @Test
    void rejectsBundleWithEmbeddingAndClassifierContracts() {
        String digest = "0".repeat(64);
        OnnxEmbeddingBundleSpec embedding = new OnnxEmbeddingBundleSpec(
                "v1",
                "input_ids",
                "attention_mask",
                null,
                "embedding",
                8,
                OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_STRATEGY,
                OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_REVISION,
                8,
                0,
                OnnxEmbeddingBundleSpec.SUPPORTED_MULTI_WINDOW_AGGREGATION,
                2,
                0L,
                OnnxEmbeddingBundleSpec.PoolingStrategy.DIRECT_VECTOR,
                true,
                "space",
                "v1",
                List.of("warm-up"));
        OnnxClassifierBundleSpec classifier = new OnnxClassifierBundleSpec(
                "v1",
                "input_ids",
                "attention_mask",
                null,
                "logits",
                8,
                0L,
                List.of("coordinator", "coder"),
                OnnxClassifierBundleSpec.ScoreTransform.SOFTMAX,
                0.5f,
                "intent-v1",
                List.of("warm-up"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxModelBundleManifest(
                        "test-bundle",
                        "revision-1",
                        "model.onnx",
                        digest,
                        1,
                        "tokenizer.json",
                        digest,
                        1,
                        0,
                        embedding,
                        classifier,
                        source()));
    }

    @Test
    void readsAllowlistedUnsignedSourceDeclaration() throws Exception {
        OnnxModelBundleManifest manifest = objectMapper.readValue("""
                {"bundleId":"test-bundle","revision":"revision-1","modelFile":"model.onnx",
                "modelSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "modelBytes":1,"tokenizerFile":"tokenizer.json",
                "tokenizerSha256":"0000000000000000000000000000000000000000000000000000000000000000",
                "tokenizerBytes":1,"source":{"repository":"https://example.test/model",
                "revision":"revision-1","modelPath":"onnx/model.onnx",
                "tokenizerPath":"tokenizer.json","license":"Apache-2.0"}}
                """, OnnxModelBundleManifest.class);

        assertEquals(
                "https://example.test/model",
                manifest.sourceSpec().orElseThrow().repository());
    }

    @Test
    void rejectsSourceLicenseOutsidePhaseZeroAllowlist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxModelBundleManifest.OnnxModelBundleSource(
                        "https://example.test/model",
                        "revision-1",
                        "onnx/model.onnx",
                        "tokenizer.json",
                        "Proprietary"));
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
                        OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_STRATEGY,
                        OnnxEmbeddingBundleSpec.SUPPORTED_CHUNKING_REVISION,
                        512,
                        0,
                        OnnxEmbeddingBundleSpec.SUPPORTED_MULTI_WINDOW_AGGREGATION,
                        768,
                        0L,
                        OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN,
                        true,
                        "sentence-transformer/example",
                        "v1",
                        List.of("warm-up")));
    }

    @Test
    void rejectsUnsupportedEmbeddingChunkingContract() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OnnxEmbeddingBundleSpec(
                        "v1",
                        "input_ids",
                        "attention_mask",
                        null,
                        "last_hidden_state",
                        512,
                        "MEAN_OVERLAPPING_WINDOWS",
                        "v1",
                        512,
                        32,
                        "WEIGHTED_MEAN",
                        768,
                        0L,
                        OnnxEmbeddingBundleSpec.PoolingStrategy.MASKED_MEAN,
                        true,
                        "sentence-transformer/example",
                        "v1",
                        List.of("warm-up")));
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
                Files.size(tokenizer),
                0,
                null,
                null,
                source());
        objectMapper.writeValue(bundle.resolve("manifest.json").toFile(), manifest);
        return bundle;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static OnnxModelBundleManifest.OnnxModelBundleSource source() {
        return new OnnxModelBundleManifest.OnnxModelBundleSource(
                "https://example.test/model", "revision-1", "model.onnx", "tokenizer.json", "Apache-2.0");
    }
}
