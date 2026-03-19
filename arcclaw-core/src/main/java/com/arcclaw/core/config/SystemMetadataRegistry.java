package com.arcclaw.core.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

@ApplicationScoped
public class SystemMetadataRegistry {

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "arcclaw")
    String appName;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String appVersion;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String activeProfile;

    private String startedAt;

    @PostConstruct
    void init() {
        startedAt = Instant.now().toString();
    }

    public String getAppName()    { return appName; }
    public String getAppVersion() { return appVersion; }
    public String getStartedAt()  { return startedAt; }
    public String getEnvironment() { return activeProfile; }

    public SystemInfo toInfo() {
        return new SystemInfo(appName, appVersion, activeProfile, startedAt);
    }

    public record SystemInfo(
            String name,
            String version,
            String environment,
            String startedAt
    ) {}
}