package com.amsha.localml.onnx;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Immutable, locally stored manifest for one ONNX model bundle. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnnxModelBundleManifest(
        String bundleId,
        String revision,
        String modelFile,
        String modelSha256,
        long modelBytes,
        String tokenizerFile,
        String tokenizerSha256,
        long tokenizerBytes,
        OnnxEmbeddingBundleSpec embedding) {

    public OnnxModelBundleManifest {
        bundleId = requireIdentifier(bundleId, "bundleId");
        revision = requireNonBlank(revision, "revision");
        modelFile = requireRelativeFile(modelFile, "modelFile");
        modelSha256 = requireSha256(modelSha256, "modelSha256");
        if (modelBytes <= 0) throw new IllegalArgumentException("modelBytes must be positive");
        tokenizerFile = requireRelativeFile(tokenizerFile, "tokenizerFile");
        if (modelFile.equals(tokenizerFile)) {
            throw new IllegalArgumentException("modelFile and tokenizerFile must be different");
        }
        tokenizerSha256 = requireSha256(tokenizerSha256, "tokenizerSha256");
        if (tokenizerBytes <= 0) throw new IllegalArgumentException("tokenizerBytes must be positive");
    }

    /** Compatibility constructor for non-embedding bundles verified by the Phase 0 foundation. */
    public OnnxModelBundleManifest(
            String bundleId,
            String revision,
            String modelFile,
            String modelSha256,
            long modelBytes,
            String tokenizerFile,
            String tokenizerSha256,
            long tokenizerBytes) {
        this(
                bundleId,
                revision,
                modelFile,
                modelSha256,
                modelBytes,
                tokenizerFile,
                tokenizerSha256,
                tokenizerBytes,
                null);
    }

    /** Returns the embedding contract when this bundle is explicitly declared for embeddings. */
    public Optional<OnnxEmbeddingBundleSpec> embeddingSpec() {
        return Optional.ofNullable(embedding);
    }

    private static String requireIdentifier(String value, String name) {
        String result = requireNonBlank(value, name);
        if (!result.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return result;
    }

    private static String requireRelativeFile(String value, String name) {
        String result = requireNonBlank(value, name);
        if (result.startsWith("/") || result.startsWith("\\\\") || result.contains("..") || result.contains("\\\\")) {
            throw new IllegalArgumentException(name + " must be a safe relative path");
        }
        return result;
    }

    private static String requireSha256(String value, String name) {
        String result = requireNonBlank(value, name).toLowerCase(Locale.ROOT);
        if (!result.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
        return result;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
