package com.arcclaw.core.health;

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

@Liveness
@ApplicationScoped
public class PodmanImageHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(PodmanImageHealthCheck.class);

    @ConfigProperty(name = "arcclaw.sandbox.container-image", defaultValue = "docker.io/library/alpine:latest")
    protected String containerImage;

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "arcclaw.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;
   

    @Override
    public HealthCheckResponse call() {
        if (!sandboxEnabled) {
            log.warn("Sandbox is disabled");
            return HealthCheckResponse
                .named("Podman Image: " + containerImage)
                .up()
                .withData("status", "sandbox disabled — skipped")
                .build();
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(podmanPath, "image", "exists", containerImage);
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
