package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

class PodmanHealthCheckTest {

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
}
