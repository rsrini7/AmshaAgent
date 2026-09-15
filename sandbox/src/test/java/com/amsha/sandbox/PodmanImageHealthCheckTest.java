package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the sandbox-image readiness probe, driven with a <b>fake podman executable</b> — a POSIX
 * shell script written to a temp directory — so the real {@code Process} plumbing runs rather than
 * being stubbed.
 *
 * <p>The probe opens with an unconditional early return when {@code sandboxEnabled} is false, making
 * the later {@code if (!sandboxEnabled)} arm and the false half of each
 * {@code sandboxEnabled ? … : …} hint unreachable. Those lines are reported as dead code.
 */
class PodmanImageHealthCheckTest {

    private PodmanImageHealthCheck healthCheck;

    /** Writes an executable POSIX shell script to stand in for the podman binary. */
    private static Path fakePodman(Path dir, String body) throws IOException {
        Path exe = dir.resolve("podman-image-" + System.nanoTime() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "fake podman must be executable");
        return exe;
    }

    @BeforeEach
    void setUp() {
        healthCheck = new PodmanImageHealthCheck();
        healthCheck.podmanPath = "non_existent_binary_12345";
        healthCheck.configuredPodmanImage = Optional.of("localhost/amsha-tool-runner:latest");
        healthCheck.sandboxEnabled = true;
    }

    @Test
    void testDisabledSandbox() {
        healthCheck.sandboxEnabled = false;
        HealthCheckResponse response = healthCheck.call();

        assertNotNull(response);
        assertEquals("Podman Image: localhost/amsha-tool-runner:latest", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("DISABLED", response.getData().orElseThrow().get("status"));
    }

    @Test
    void testMissingBinaryReturnsDown() {
        HealthCheckResponse response = healthCheck.call();

        assertNotNull(response);
        assertEquals("Podman Image: localhost/amsha-tool-runner:latest", response.getName());
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    @Test
    void testImageCheckProcessTimeout(@TempDir Path dir) throws IOException {
        healthCheck.podmanPath = fakePodman(dir, "sleep 8").toString();

        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertTrue(
                String.valueOf(response.getData().orElseThrow().get("error")).contains("timed out"),
                "expected a timeout diagnosis, got: " + response.getData());
    }

    @Test
    void testAbsentImageReturnsDownWithAPullHint(@TempDir Path dir) throws IOException {
        healthCheck.podmanPath = fakePodman(dir, "exit 125").toString();

        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals("image not found", response.getData().orElseThrow().get("error"));
        assertTrue(String.valueOf(response.getData().orElseThrow().get("hint")).contains("podman pull"));
    }

    @Test
    void testOverlongErrorIsTruncated() {
        healthCheck.podmanPath = "missing-image-binary-" + "x".repeat(250);

        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals(
                200,
                String.valueOf(response.getData().orElseThrow().get("error")).length());
    }

    @Test
    void testExceptionWithNoMessageIsReportedAsUnknown() {
        healthCheck.podmanPath = null;

        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals("unknown error", response.getData().orElseThrow().get("error"));
    }
}
