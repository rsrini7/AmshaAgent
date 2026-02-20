package com.arcclaw.core.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.util.concurrent.TimeUnit;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.Startup;

@Startup
@Liveness
@ApplicationScoped
@Unremovable
public class PodmanHealthCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        try {
            ProcessBuilder pb = new ProcessBuilder("podman", "--version");
            Process process = pb.start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                return HealthCheckResponse.named("Podman Engine").up().build();
            } else {
                if (!finished) {
                    process.destroyForcibly();
                    return HealthCheckResponse.named("Podman Engine").down()
                            .withData("error", "Process timed out after 2 seconds")
                            .build();
                }
                return HealthCheckResponse.named("Podman Engine").down()
                        .withData("error", "Process exited with code " + process.exitValue())
                        .build();
            }
        } catch (Exception e) {
            return HealthCheckResponse.named("Podman Engine").down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
