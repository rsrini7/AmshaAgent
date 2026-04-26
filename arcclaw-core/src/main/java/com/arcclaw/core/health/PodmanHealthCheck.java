package com.arcclaw.core.health;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

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
                .withData("status", "DISABLED")
                .withData("sandbox-enabled", "false")
                .build();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(podmanPath, "ps", "--format", "json");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return HealthCheckResponse.named("Podman Engine")
                    .down()
                    .withData("error", "Process timed out — Podman machine may be stopped")
                    .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                    .withData("hint", sandboxEnabled
                        ? "Run: podman machine start"
                        : "Sandbox is disabled — set ARCCLAW_SANDBOX_ENABLED=true in .env to enable")
                    .build();
            }

            if (process.exitValue() == 0) {
                if (!sandboxEnabled) {
                    return HealthCheckResponse.named("Podman Engine")
                        .up()
                        .withData("status", "Podman is running but sandbox is disabled")
                        .withData("sandbox-enabled", "false")
                        .withData("hint", "Set ARCCLAW_SANDBOX_ENABLED=true in .env or " +
                                "arcclaw.sandbox.enabled=true in application.yaml to enable")
                        .build();
                }
                return HealthCheckResponse.named("Podman Engine")
                    .up()
                    .withData("status", "running")
                    .withData("sandbox-enabled", "true")
                    .build();
            }

            String err = new String(process.getInputStream().readAllBytes()).trim();
            return HealthCheckResponse.named("Podman Engine")
                .down()
                .withData("error", err.length() > 200 ? err.substring(0, 200) : err)
                .withData("sandbox-enabled", String.valueOf(sandboxEnabled))
                .withData("hint", sandboxEnabled
                    ? "Run: podman machine start"
                    : "Sandbox is disabled — set ARCCLAW_SANDBOX_ENABLED=true in .env to enable")
                .build();

        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Engine")
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
