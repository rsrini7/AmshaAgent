package com.amsha.localml.onnx;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves only locally installed, checksum-verified model bundles beneath the
 * configured model root. It deliberately performs no download or installation.
 */
@ApplicationScoped
public class OnnxModelBundleRegistry {

    private final boolean enabled;
    private final Path modelRoot;
    private final OnnxModelBundleVerifier verifier;

    @Inject
    public OnnxModelBundleRegistry(
            ObjectMapper objectMapper,
            @ConfigProperty(name = "amsha.onnx.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "amsha.onnx.model-root", defaultValue = "data/models") String modelRoot,
            @ConfigProperty(name = "amsha.onnx.max-model-file-bytes", defaultValue = "1073741824")
                    long maxModelFileBytes) {
        this(objectMapper, enabled, Path.of(modelRoot), maxModelFileBytes);
    }

    OnnxModelBundleRegistry(ObjectMapper objectMapper, boolean enabled, Path modelRoot, long maxModelFileBytes) {
        this.enabled = enabled;
        this.modelRoot =
                Objects.requireNonNull(modelRoot, "modelRoot").toAbsolutePath().normalize();
        verifier = new OnnxModelBundleVerifier(Objects.requireNonNull(objectMapper, "objectMapper"), maxModelFileBytes);
    }

    /**
     * Loads one explicitly selected local bundle. A disabled feature, missing root, invalid
     * bundle ID, or path outside the root is rejected before parsing a manifest.
     */
    public VerifiedOnnxModelBundle loadVerified(String bundleId) throws IOException {
        if (!enabled) throw new IOException("Local ONNX inference is disabled");
        validateBundleId(bundleId);
        Path canonicalRoot = modelRoot.toRealPath();
        if (!Files.isDirectory(canonicalRoot)) throw new IOException("ONNX model root must be a directory");
        Path bundle = canonicalRoot.resolve(bundleId).normalize();
        if (!bundle.startsWith(canonicalRoot)) throw new IOException("Model bundle path escapes configured root");
        Path canonicalBundle = bundle.toRealPath();
        if (!canonicalBundle.startsWith(canonicalRoot)) {
            throw new IOException("Model bundle path escapes configured root");
        }
        VerifiedOnnxModelBundle verified = verifier.loadAndVerify(canonicalBundle);
        if (!bundleId.equals(verified.manifest().bundleId())) {
            throw new IOException("Model bundle ID does not match its directory selection");
        }
        return verified;
    }

    private static void validateBundleId(String bundleId) throws IOException {
        if (bundleId == null || !bundleId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IOException("Model bundle ID contains unsupported characters");
        }
    }
}
