package com.arcclaw.core.health;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class PodmanImageHealthCheck implements HealthCheck {

    @ConfigProperty(name = "arcclaw.sandbox.podman-image", defaultValue = "localhost/arcclaw-tool-runner:latest")
    String containerImage;

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "arcclaw.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;

    @Override
    public HealthCheckResponse call() {
        try {
            ProcessBuilder pb = new ProcessBuilder(podmanPath, "image", "inspect", containerImage, "--format", "{{.Id}}");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .down()
                    .withData("error", "image check timed out — Podman machine may be stopped")
                    .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                    .withData("hint", sandboxEnabled
                        ? "Run: podman machine start"
                        : "Sandbox is disabled — set ARCCLAW_SANDBOX_ENABLED=true in .env to enable")
                    .build();
            }

            if (process.exitValue() == 0) {
                if (!sandboxEnabled) {
                    return HealthCheckResponse.named("Podman Image: " + containerImage)
                        .up()
                        .withData("status", "image present but sandbox is disabled")
                        .withData("sandbox-enabled", "false")
                        .withData("hint", "Set ARCCLAW_SANDBOX_ENABLED=true in .env or " +
                                "arcclaw.sandbox.enabled=true in application.yaml to enable")
                        .build();
                }
                return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .up()
                    .withData("image", containerImage)
                    .withData("sandbox-enabled", "true")
                    .build();
            }

            // exit != 0 means image not found
            return HealthCheckResponse.named("Podman Image: " + containerImage)
                .down()
                .withData("error", "image not found")
                .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                .withData("hint", sandboxEnabled
                    ? "Run: podman pull " + containerImage
                    : "Sandbox is disabled — set ARCCLAW_SANDBOX_ENABLED=true in .env to enable")
                .build();

        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Image: " + containerImage)
                .down()
                .withData("error", e.getMessage() != null
                    ? (e.getMessage().length() > 200
                        ? e.getMessage().substring(0, 200)
                        : e.getMessage())
                    : "unknown error")
                .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                .withData("hint", sandboxEnabled
                    ? "Run: podman machine start"
                    : "Sandbox is disabled — set ARCCLAW_SANDBOX_ENABLED=true in .env to enable")
                .build();
        }
    }
}
