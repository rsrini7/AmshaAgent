package com.arcclaw.core.health;

import java.util.concurrent.TimeUnit;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;

@Readiness
@ApplicationScoped
public class PodmanHealthCheck implements HealthCheck {

    @ConfigProperty(name = "arcclaw.sandbox.podman-path", defaultValue = "podman")
    String podmanPath;

    @ConfigProperty(name = "arcclaw.sandbox.enabled", defaultValue = "true")
    boolean sandboxEnabled;

    @Override
    public HealthCheckResponse call() {
        if (!sandboxEnabled) {
            return HealthCheckResponse.named("Podman Engine")
                    .up()
                    .withData("status", "sandbox disabled — skipped")
                    .build();
        }

        try {
            // `podman ps` requires an active machine connection — fails if VM is stopped.
            // `podman --version` only checks the binary exists — always succeeds.
            ProcessBuilder pb = new ProcessBuilder(podmanPath, "ps", "--format", "json");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                return HealthCheckResponse.named("Podman Engine").up().build();
            } else {
                if (!finished) {
                    process.destroyForcibly();
                    return HealthCheckResponse.named("Podman Engine").down()
                            .withData("error", "Process timed out — machine may be stopped")
                            .build();
                }
                // Read stderr for a clean error message
                String err = new String(process.getInputStream().readAllBytes()).trim();
                return HealthCheckResponse.named("Podman Engine").down()
                        .withData("error", err.length() > 200 ? err.substring(0, 200) : err)
                        .build();
            }
        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Engine").down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
