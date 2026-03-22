package com.arcclaw.app.health;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Test-only replacement for PodmanHealthCheck.
 * Uses @Alternative + @Priority to override the production bean via CDI.
 */
@Alternative
@Priority(1)
@Liveness
@ApplicationScoped
public class MockPodmanHealthCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("Podman Engine").up().build();
    }
}
