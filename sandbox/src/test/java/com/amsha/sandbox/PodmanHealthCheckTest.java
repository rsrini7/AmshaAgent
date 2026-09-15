package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the readiness probes. Both probes are driven with a <b>fake podman executable</b> — a
 * POSIX shell script written to a temp directory — so the real {@code Process} plumbing (start,
 * wait, exit code, stderr capture) runs instead of being stubbed out.
 *
 * <p>Each probe opens with an unconditional early return when {@code sandboxEnabled} is false, which
 * makes the later {@code if (!sandboxEnabled)} arms and the false half of every
 * {@code sandboxEnabled ? … : …} hint unreachable. Those lines are reported as dead code rather than
 * faked; see the per-class coverage notes.
 */
class PodmanHealthCheckTest {

    /** Writes an executable POSIX shell script to stand in for the podman binary. */
    private static Path fakePodman(Path dir, String body) throws IOException {
        Path exe = dir.resolve("podman-" + System.nanoTime() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "fake podman must be executable");
        return exe;
    }

    @Test
    void testPodmanHealthCheckDisabled() {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = false;
        check.podmanPath = "podman";

        HealthCheckResponse resp = check.call();
        assertNotNull(resp);
        assertEquals(HealthCheckResponse.Status.UP, resp.getStatus());
        assertEquals("DISABLED", resp.getData().orElseThrow().get("status"));
    }

    @Test
    void testPodmanHealthCheckNotFound() {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = true;
        check.podmanPath = "non_existent_binary_xyz_123";

        HealthCheckResponse resp = check.call();
        assertNotNull(resp);
        assertEquals(HealthCheckResponse.Status.DOWN, resp.getStatus());
    }

    @Test
    void testPodmanHealthCheckProcessExit0() {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = true;
        check.podmanPath = "echo";

        HealthCheckResponse resp = check.call();
        assertNotNull(resp);
        assertEquals(HealthCheckResponse.Status.UP, resp.getStatus());
        assertEquals("running", resp.getData().orElseThrow().get("status"));
    }

    @Test
    void testPodmanImageHealthCheckDisabled() {
        PodmanImageHealthCheck check = new PodmanImageHealthCheck();
        check.sandboxEnabled = false;
        check.configuredPodmanImage = Optional.of("localhost/amsha-tool-runner:latest");
        check.podmanPath = "podman";

        HealthCheckResponse resp = check.call();
        assertNotNull(resp);
        assertEquals(HealthCheckResponse.Status.UP, resp.getStatus());
        assertEquals("DISABLED", resp.getData().orElseThrow().get("status"));
    }

    @Test
    void testPodmanImageHealthCheckNotFound() {
        PodmanImageHealthCheck check = new PodmanImageHealthCheck();
        check.sandboxEnabled = true;
        check.configuredPodmanImage = Optional.of("localhost/amsha-tool-runner:latest");
        check.podmanPath = "non_existent_binary_xyz_123";

        HealthCheckResponse resp = check.call();
        assertNotNull(resp);
        assertEquals(HealthCheckResponse.Status.DOWN, resp.getStatus());
    }

    @Test
    void testPodmanImageHealthCheckProcessExit0() {
        PodmanImageHealthCheck check = new PodmanImageHealthCheck();
        check.sandboxEnabled = true;
        check.configuredPodmanImage = Optional.of("localhost/amsha-tool-runner:latest");
        check.podmanPath = "echo";

        HealthCheckResponse resp = check.call();
        assertNotNull(resp);
        assertEquals(HealthCheckResponse.Status.UP, resp.getStatus());
    }

    @Test
    void testPodmanHealthCheckProcessTimeout(@TempDir Path dir) throws IOException {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = true;
        check.podmanPath = fakePodman(dir, "sleep 5").toString();

        HealthCheckResponse resp = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, resp.getStatus());
        assertTrue(
                String.valueOf(resp.getData().orElseThrow().get("error")).contains("timed out"),
                "expected a timeout diagnosis, got: " + resp.getData());
    }

    @Test
    void testPodmanHealthCheckReportsAFailedEngine(@TempDir Path dir) throws IOException {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = true;
        check.podmanPath = fakePodman(dir, "echo 'cannot connect to the podman socket'; exit 125")
                .toString();

        HealthCheckResponse resp = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, resp.getStatus());
        assertTrue(String.valueOf(resp.getData().orElseThrow().get("error")).contains("cannot connect"));
    }

    @Test
    void testPodmanHealthCheckTruncatesAnOverlongError() {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = true;
        check.podmanPath = "missing-podman-" + "x".repeat(250);

        HealthCheckResponse resp = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, resp.getStatus());
        assertEquals(
                200, String.valueOf(resp.getData().orElseThrow().get("error")).length());
    }

    @Test
    void testPodmanHealthCheckHandlesAnExceptionWithNoMessage() {
        PodmanHealthCheck check = new PodmanHealthCheck();
        check.sandboxEnabled = true;
        check.podmanPath = null;

        HealthCheckResponse resp = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, resp.getStatus());
        assertEquals("unknown error", resp.getData().orElseThrow().get("error"));
    }
}
