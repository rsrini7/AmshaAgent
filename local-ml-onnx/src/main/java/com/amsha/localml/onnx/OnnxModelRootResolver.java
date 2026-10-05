package com.amsha.localml.onnx;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single canonical rule set for locating the local ONNX model root.
 *
 * <p>This exists because the installer CLI and the runtime registry previously resolved the same
 * setting by two different, subtly incompatible chains. The CLI probed {@code ../} when a relative
 * root was not present in the working directory; the runtime did not, and simply normalized
 * against the CWD. Running the native binary from {@code dist/} therefore installed bundles that
 * the running process then failed to find, and {@code models install} reported success because only
 * its own resolver knew about the parent-directory fallback.
 *
 * <p>Both consumers now delegate here, so an install can never land somewhere the runtime will not
 * look. Precedence is <strong>system property before environment variable</strong>, matching
 * Quarkus/MicroProfile ordinals: {@code amsha.onnx.model-root} outranks {@code AMSHA_ONNX_MODEL_ROOT},
 * and both outrank {@code AMSHA_DATA_DIR}. The previous chain read the model root environment-first
 * but the data dir property-first, which meant one method disagreed with itself and with the runtime.
 *
 * <p>An explicitly configured value is never silently replaced. A relative path that exists is
 * absolutized; a relative path that does not exist is retried one level up (the {@code dist/}
 * layout, where the binary sits beside rather than inside the data directory); if neither exists the
 * configured path is returned as-is so the caller reports the operator's own value in its error.
 */
public final class OnnxModelRootResolver {

    /** MicroProfile/System property naming the model root. */
    public static final String MODEL_ROOT_PROPERTY = "amsha.onnx.model-root";

    /** Environment variable naming the model root. */
    public static final String MODEL_ROOT_ENV = "AMSHA_ONNX_MODEL_ROOT";

    /** Property and environment variable naming the data directory. */
    public static final String DATA_DIR_NAME = "AMSHA_DATA_DIR";

    private static final String DEFAULT_RELATIVE_ROOT = "data/models";

    private OnnxModelRootResolver() {}

    /**
     * Resolves the model root from the process environment for callers that run before — or entirely
     * outside — the CDI container, such as the {@code models} CLI fast path.
     *
     * @return an absolute, normalized model root; never {@code null}
     */
    public static Path resolve() {
        String configured = firstNonBlank(System.getProperty(MODEL_ROOT_PROPERTY), System.getenv(MODEL_ROOT_ENV));
        if (configured != null) {
            return normalize(configured);
        }
        String dataDir = firstNonBlank(System.getProperty(DATA_DIR_NAME), System.getenv(DATA_DIR_NAME));
        if (dataDir != null) {
            return normalize(Path.of(dataDir.trim()).resolve("models").toString());
        }
        return normalize(DEFAULT_RELATIVE_ROOT);
    }

    /**
     * Applies the canonical normalization to an already-resolved configuration value. Callers that
     * receive the model root through MicroProfile use this, so that a relative value behaves
     * identically to one this class resolved itself.
     *
     * @param configuredRoot the configured model root; {@code null} or blank falls back to the default
     * @return an absolute, normalized model root; never {@code null}
     */
    public static Path normalize(String configuredRoot) {
        return normalize(configuredRoot, startupDirectory());
    }

    /**
     * Normalizes against an explicit base directory instead of the process working directory.
     *
     * <p>Exposed so the rules can be asserted against a synthetic {@code dist/} layout. It is a real
     * seam rather than test-only convenience: {@link Path#toAbsolutePath()} resolves against the
     * working directory captured when the JVM started, so a relative root cannot be re-pointed at a
     * temporary directory by changing {@code user.dir} afterwards. Asserting the fallback therefore
     * requires supplying the base explicitly.
     *
     * @param configuredRoot the configured model root; {@code null} or blank falls back to the default
     * @param base the directory a relative root is resolved against
     * @return an absolute, normalized model root; never {@code null}
     */
    public static Path normalize(String configuredRoot, Path base) {
        String value =
                configuredRoot == null || configuredRoot.isBlank() ? DEFAULT_RELATIVE_ROOT : configuredRoot.trim();
        Path candidate = Path.of(value);
        if (candidate.isAbsolute()) {
            return candidate.normalize();
        }
        Path local = base.resolve(candidate).normalize();
        if (Files.isDirectory(local)) {
            return local;
        }
        Path oneLevelUp = base.resolve("..").resolve(candidate).normalize();
        if (Files.isDirectory(oneLevelUp)) {
            return oneLevelUp;
        }
        return local;
    }

    private static Path startupDirectory() {
        return Path.of("").toAbsolutePath();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
