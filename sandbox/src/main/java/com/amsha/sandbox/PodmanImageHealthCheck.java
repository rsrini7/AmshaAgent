package com.amsha.sandbox;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class PodmanImageHealthCheck implements HealthCheck {

    @ConfigProperty(name = "amsha.sandbox.image")
    Optional<String> configuredSandboxImage = Optional.empty();

    @ConfigProperty(name = "amsha.sandbox.podman-image", defaultValue = SandboxImageResolver.DEFAULT_IMAGE)
    Optional<String> configuredPodmanImage = Optional.empty();

    String getImageName() {
        return SandboxImageResolver.resolveImage(configuredSandboxImage, configuredPodmanImage);
    }

    @ConfigProperty(name = "amsha.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "amsha.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;

    @Override
    @SuppressWarnings("java:S1192")
    public HealthCheckResponse call() {
        String containerImage = getImageName();
        if (!sandboxEnabled) {
            return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .up()
                    .withData("status", "DISABLED")
                    .withData("sandbox-enabled", "false")
                    .build();
        }
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(podmanPath, "image", "inspect", containerImage, "--format", "{{.Id}}");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return HealthCheckResponse.named("Podman Image: " + containerImage)
                        .down()
                        .withData("error", "image check timed out - Podman machine may be stopped")
                        .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                        .withData(
                                "hint",
                                sandboxEnabled
                                        ? "Run: podman machine start"
                                        : "Sandbox is disabled - set AMSHA_SANDBOX_ENABLED=true in .env to enable")
                        .build();
            }

            if (process.exitValue() == 0) {
                if (!sandboxEnabled) {
                    return HealthCheckResponse.named("Podman Image: " + containerImage)
                            .up()
                            .withData("status", "image present but sandbox is disabled")
                            .withData("sandbox-enabled", "false")
                            .withData(
                                    "hint",
                                    "Set AMSHA_SANDBOX_ENABLED=true in .env or "
                                            + "amsha.sandbox.enabled=true in application.yaml to enable")
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
                    .withData(
                            "hint",
                            sandboxEnabled
                                    ? "Run: podman pull " + containerImage
                                    : "Sandbox is disabled - set AMSHA_SANDBOX_ENABLED=true in .env to enable")
                    .build();

        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Image: " + containerImage)
                    .down()
                    .withData(
                            "error",
                            e.getMessage() != null
                                    ? (e.getMessage().length() > 200
                                            ? e.getMessage().substring(0, 200)
                                            : e.getMessage())
                                    : "unknown error")
                    .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                    .withData(
                            "hint",
                            sandboxEnabled
                                    ? "Run: podman machine start"
                                    : "Sandbox is disabled - set AMSHA_SANDBOX_ENABLED=true in .env to enable")
                    .build();
        }
    }
}
