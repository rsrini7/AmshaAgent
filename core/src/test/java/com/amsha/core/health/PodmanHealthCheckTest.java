package com.amsha.core.health;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodmanHealthCheckTest {

    private PodmanHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        healthCheck = new PodmanHealthCheck();
        healthCheck.podmanPath = "non_existent_binary_12345";
        healthCheck.sandboxEnabled = true;
    }

    @Test
    void testDisabledSandbox() {
        healthCheck.sandboxEnabled = false;
        HealthCheckResponse response = healthCheck.call();

        assertNotNull(response);
        assertEquals("Podman Engine", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("DISABLED", response.getData().orElseThrow().get("status"));
    }

    @Test
    void testMissingBinaryReturnsDown() {
        HealthCheckResponse response = healthCheck.call();

        assertNotNull(response);
        assertEquals("Podman Engine", response.getName());
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
