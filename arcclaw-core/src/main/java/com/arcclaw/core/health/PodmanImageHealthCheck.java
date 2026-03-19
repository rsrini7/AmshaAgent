package com.arcclaw.core.health;

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;

@Readiness
@ApplicationScoped
public class PodmanImageHealthCheck implements HealthCheck {

    @ConfigProperty(name = "arcclaw.sandbox.container-image", defaultValue = "docker.io/library/alpine:latest")
    protected String containerImage;

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "arcclaw.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;
   
    @Override
    public HealthCheckResponse call() {
        try {
            ProcessBuilder pb = new ProcessBuilder(podmanPath, "image", "exists", containerImage);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .down()
                    .withData("error", "image check timed out — Podman machine may be stopped")
                    .build();
            }

            if (process.exitValue() == 0) {
                if (!sandboxEnabled) {
                    return HealthCheckResponse.named("Podman Image: " + containerImage)
                        .up()
                        .withData("status", "image present but sandbox is disabled")
                        .withData("hint", "Set ARCCLAW_SANDBOX_ENABLED=true in .env or arcclaw.sandbox.enabled=true in application.yaml to enable")
                        .build();
                }
                return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .up()
                    .withData("image", containerImage)
                    .build();
            }

            // exit != 0 means image not found
            if (!sandboxEnabled) {
                return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .down()
                    .withData("error", "image not found")
                    .withData("hint", "Run: podman pull " + containerImage)
                    .build();
            }
            return HealthCheckResponse.named("Podman Image: " + containerImage)
                .down()
                .withData("error", "image not found — run: podman pull " + containerImage)
                .build();

        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Image: " + containerImage)
                .down()
                .withData("error", e.getMessage())
                .build();
        }
    }
}
