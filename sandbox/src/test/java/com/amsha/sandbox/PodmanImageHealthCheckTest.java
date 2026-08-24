package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodmanImageHealthCheckTest {

    private PodmanImageHealthCheck healthCheck;

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
}
