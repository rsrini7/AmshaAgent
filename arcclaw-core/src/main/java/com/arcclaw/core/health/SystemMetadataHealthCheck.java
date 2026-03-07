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
        return HealthCheckResponse
                .named("System Metadata")
                .up()
                .withData("version", registry.getAppVersion())
                .withData("environment", registry.getEnvironment())
                .withData("startedAt", registry.getStartedAt())
                .build();
    }
}
