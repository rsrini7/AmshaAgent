package com.amsha.localml.onnx;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
        long estimatedResidentBytes,
        OnnxEmbeddingBundleSpec embedding,
        OnnxClassifierBundleSpec classifier,
        OnnxScoringBundleSpec scoring,
        OnnxModelBundleSource source) {

    private static final Set<String> ALLOWED_SOURCE_LICENSES =
            Set.of("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD");

    public OnnxModelBundleManifest {
        bundleId = requireIdentifier(bundleId, "bundleId");
        revision = requireNonBlank(revision, "revision");
        if (source == null) {
            throw new IllegalArgumentException("source metadata is required");
        }
        modelFile = requireRelativeFile(modelFile, "modelFile");
        modelSha256 = requireSha256(modelSha256, "modelSha256");
        if (modelBytes <= 0) throw new IllegalArgumentException("modelBytes must be positive");
        tokenizerFile = requireRelativeFile(tokenizerFile, "tokenizerFile");
        if (modelFile.equals(tokenizerFile)) {
            throw new IllegalArgumentException("modelFile and tokenizerFile must be different");
        }
        tokenizerSha256 = requireSha256(tokenizerSha256, "tokenizerSha256");
        if (tokenizerBytes <= 0) throw new IllegalArgumentException("tokenizerBytes must be positive");
        long verifiedBundleBytes = addExact(modelBytes, tokenizerBytes, "verified bundle bytes overflow");
        if (estimatedResidentBytes == 0) {
            estimatedResidentBytes = multiplyExact(verifiedBundleBytes, 2, "default resident estimate overflow");
        }
        if (estimatedResidentBytes < verifiedBundleBytes) {
            throw new IllegalArgumentException("estimatedResidentBytes must cover all verified bundle files");
        }
        int declaredContracts = 0;
        if (embedding != null) declaredContracts++;
        if (classifier != null) declaredContracts++;
        if (scoring != null) declaredContracts++;
        if (declaredContracts > 1) {
            throw new IllegalArgumentException(
                    "A model bundle can declare at most one of embedding, classifier, or scoring contracts");
        }
    }

    /**
     * Descriptive upstream source declaration. This is not a signature, trust root, SBOM,
     * or release-provenance attestation; those remain external release evidence.
     */
    public record OnnxModelBundleSource(
            String repository, String revision, String modelPath, String tokenizerPath, String license) {
        public OnnxModelBundleSource {
            repository = requireHttpsRepository(repository);
            revision = requireNonBlank(revision, "revision");
            modelPath = requireRelativeFile(modelPath, "modelPath");
            tokenizerPath = requireRelativeFile(tokenizerPath, "tokenizerPath");
            license = requireNonBlank(license, "license");
            if (!ALLOWED_SOURCE_LICENSES.contains(license)) {
                throw new IllegalArgumentException("license is not in the Phase 0 SPDX allowlist");
            }
        }
    }

    /** Returns unsigned descriptive source metadata when the local manifest declares it. */
    public Optional<OnnxModelBundleSource> sourceSpec() {
        return Optional.of(source);
    }

    /** Compatibility constructor for manifests created before scoring contracts were added. */
    public OnnxModelBundleManifest(
            String bundleId,
            String revision,
            String modelFile,
            String modelSha256,
            long modelBytes,
            String tokenizerFile,
            String tokenizerSha256,
            long tokenizerBytes,
            long estimatedResidentBytes,
            OnnxEmbeddingBundleSpec embedding,
            OnnxClassifierBundleSpec classifier,
            OnnxModelBundleSource source) {
        this(
                bundleId,
                revision,
                modelFile,
                modelSha256,
                modelBytes,
                tokenizerFile,
                tokenizerSha256,
                tokenizerBytes,
                estimatedResidentBytes,
                embedding,
                classifier,
                null,
                source);
    }

    /** Compatibility constructor for manifests created before classifier contracts were added. */
    public OnnxModelBundleManifest(
            String bundleId,
            String revision,
            String modelFile,
            String modelSha256,
            long modelBytes,
            String tokenizerFile,
            String tokenizerSha256,
            long tokenizerBytes,
            OnnxEmbeddingBundleSpec embedding,
            OnnxModelBundleSource source) {
        this(
                bundleId,
                revision,
                modelFile,
                modelSha256,
                modelBytes,
                tokenizerFile,
                tokenizerSha256,
                tokenizerBytes,
                0,
                embedding,
                null,
                null,
                source);
    }

    /** Returns the embedding contract when this bundle is explicitly declared for embeddings. */
    public Optional<OnnxEmbeddingBundleSpec> embeddingSpec() {
        return Optional.ofNullable(embedding);
    }

    /** Returns the classifier contract when this bundle is explicitly declared for classification. */
    public Optional<OnnxClassifierBundleSpec> classifierSpec() {
        return Optional.ofNullable(classifier);
    }

    /** Returns the scoring contract when this bundle is explicitly declared for cross-encoder reranking. */
    public Optional<OnnxScoringBundleSpec> scoringSpec() {
        return Optional.ofNullable(scoring);
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

    private static long addExact(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(message, e);
        }
    }

    private static long multiplyExact(long value, long multiplier, String message) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(message, e);
        }
    }

    private static String requireHttpsRepository(String value) {
        String result = requireNonBlank(value, "repository");
        if (!result.startsWith("https://") || result.contains(" ")) {
            throw new IllegalArgumentException("repository must be an HTTPS URL");
        }
        return result;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
