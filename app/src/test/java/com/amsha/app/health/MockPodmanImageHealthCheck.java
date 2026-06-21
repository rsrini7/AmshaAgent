package com.amsha.app.health;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Test-only replacement for PodmanImageHealthCheck.
 * Cannot extend the production class due to ArC classloader boundary issues
 * with @ConfigProperty field injection across modules.
 */
@Alternative
@Priority(1)
@Readiness
@ApplicationScoped
public class MockPodmanImageHealthCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("Podman Image: test-image").up().build();
    }
}
