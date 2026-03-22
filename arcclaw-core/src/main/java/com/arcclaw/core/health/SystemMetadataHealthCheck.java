package com.arcclaw.core.health;

import com.arcclaw.core.config.SystemMetadataRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class SystemMetadataHealthCheck implements HealthCheck {

    @Inject
    SystemMetadataRegistry registry;

    @Override
    public HealthCheckResponse call() {
        String version = registry.getAppVersion();
        String startedAt = registry.getStartedAt();

        if (version == null || startedAt == null) {
            return HealthCheckResponse.named("System Metadata")
                    .down()
                    .withData("error", "SystemMetadataRegistry not fully initialized")
                    .build();
        }

        return HealthCheckResponse.named("System Metadata")
                .up()
                .withData("version", version)
                .withData("environment", registry.getEnvironment())
                .withData("startedAt", startedAt)
                .build();
    }

}
