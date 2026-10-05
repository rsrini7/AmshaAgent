package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the canonical model-root rules that the installer CLI and the runtime registry now share.
 *
 * <p>The regression that matters is cross-consumer: the CLI resolved a relative root by probing the
 * parent directory while the runtime did not, so {@code models install} launched from {@code dist/}
 * reported success and wrote to the repository root, and the running binary then failed to find the
 * bundles it had just installed. Every case here is therefore about <em>which</em> path is chosen
 * from a given working directory, not merely that a path is returned.
 */
class OnnxModelRootResolverTest {

    @Test
    @DisplayName("an absolute configured root is returned normalized and never re-probed")
    void absoluteRootWinsOutright(@TempDir Path tmp) throws IOException {
        Path absolute = Files.createDirectories(tmp.resolve("models"));

        Path resolved = OnnxModelRootResolver.normalize(absolute.toString());

        assertEquals(absolute.toAbsolutePath().normalize(), resolved);
    }

    @Test
    @DisplayName("a relative root that exists in the working directory is used as-is")
    void existingRelativeRootIsUsedDirectly(@TempDir Path tmp) throws IOException {
        Path models = Files.createDirectories(tmp.resolve("data").resolve("models"));

        Path resolved = OnnxModelRootResolver.normalize("data/models", tmp);

        assertEquals(models.toAbsolutePath().normalize(), resolved);
    }

    @Test
    @DisplayName("a relative root absent from the working directory resolves one level up")
    void missingRelativeRootFallsBackToTheParentDirectory(@TempDir Path tmp) throws IOException {
        // The dist/ layout: the binary's working directory has no data/models, the repository root does.
        Path repoRoot = Files.createDirectories(tmp.resolve("repo"));
        Path models = Files.createDirectories(repoRoot.resolve("data").resolve("models"));
        Path workingDir = Files.createDirectories(repoRoot.resolve("dist"));

        assertTrue(Files.notExists(workingDir.resolve("data").resolve("models")));

        Path resolved = OnnxModelRootResolver.normalize("data/models", workingDir);

        assertEquals(models.toAbsolutePath().normalize(), resolved, "the parent-directory probe must apply");
    }

    @Test
    @DisplayName("a relative root that exists nowhere is returned as configured, not invented")
    void absentRelativeRootIsNotInvented(@TempDir Path tmp) throws IOException {
        Path workingDir = Files.createDirectories(tmp.resolve("elsewhere"));

        Path resolved = OnnxModelRootResolver.normalize("data/models", workingDir);

        assertEquals(
                workingDir.resolve("data").resolve("models").toAbsolutePath().normalize(),
                resolved,
                "a root that does not exist must surface the operator's own value so the error names it");
    }

    @Test
    @DisplayName("a null or blank root falls back to the default data/models location")
    void blankRootUsesTheDefault(@TempDir Path tmp) throws IOException {
        Path workingDir = Files.createDirectories(tmp.resolve("cwd"));
        Files.createDirectories(workingDir.resolve("data").resolve("models"));

        Path expected =
                workingDir.resolve("data").resolve("models").toAbsolutePath().normalize();
        assertEquals(expected, OnnxModelRootResolver.normalize(null, workingDir));
        assertEquals(expected, OnnxModelRootResolver.normalize("   ", workingDir));
    }

    @Test
    @DisplayName("the model root property outranks the environment variable")
    void propertyOutranksEnvironment(@TempDir Path tmp) throws IOException {
        Path fromProperty = Files.createDirectories(tmp.resolve("from-property"));
        String previous = System.getProperty(OnnxModelRootResolver.MODEL_ROOT_PROPERTY);
        System.setProperty(OnnxModelRootResolver.MODEL_ROOT_PROPERTY, fromProperty.toString());
        try {
            assertEquals(fromProperty.toAbsolutePath().normalize(), OnnxModelRootResolver.resolve());
        } finally {
            restore(OnnxModelRootResolver.MODEL_ROOT_PROPERTY, previous);
        }
    }

    @Test
    @DisplayName("the data directory is only consulted when no model root is configured")
    void dataDirIsTheLastResort(@TempDir Path tmp) throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Path explicit = Files.createDirectories(tmp.resolve("explicit"));
        String previousRoot = System.getProperty(OnnxModelRootResolver.MODEL_ROOT_PROPERTY);
        String previousData = System.getProperty(OnnxModelRootResolver.DATA_DIR_NAME);
        System.clearProperty(OnnxModelRootResolver.MODEL_ROOT_PROPERTY);
        System.setProperty(OnnxModelRootResolver.DATA_DIR_NAME, tmp.toString());
        try {
            assertEquals(models.toAbsolutePath().normalize(), OnnxModelRootResolver.resolve());

            System.setProperty(OnnxModelRootResolver.MODEL_ROOT_PROPERTY, explicit.toString());
            assertEquals(explicit.toAbsolutePath().normalize(), OnnxModelRootResolver.resolve());
        } finally {
            restore(OnnxModelRootResolver.MODEL_ROOT_PROPERTY, previousRoot);
            restore(OnnxModelRootResolver.DATA_DIR_NAME, previousData);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
