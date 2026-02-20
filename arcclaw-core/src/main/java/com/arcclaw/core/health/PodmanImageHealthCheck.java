package com.arcclaw.core.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.util.concurrent.TimeUnit;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

@Startup
@Readiness
@ApplicationScoped
@Unremovable
public class PodmanImageHealthCheck implements HealthCheck {

    @ConfigProperty(name = "arcclaw.sandbox.container-image", defaultValue = "docker.io/library/alpine:latest")
    String containerImage;

    @Override
    public HealthCheckResponse call() {
        try {
            ProcessBuilder pb = new ProcessBuilder("podman", "image", "exists", containerImage);
            Process process = pb.start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                return HealthCheckResponse.named("Podman Image: " + containerImage).up().build();
            } else {
                if (!finished) {
                    process.destroyForcibly();
                    return HealthCheckResponse.named("Podman Image: " + containerImage).down()
                            .withData("error", "Process timed out after 2 seconds")
                            .build();
                }
                return HealthCheckResponse.named("Podman Image: " + containerImage).down()
                        .withData("error", "Image not found or process exited with code " + process.exitValue())
                        .build();
            }
        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Image: " + containerImage).down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
