package com.arcclaw.core.config;

import io.quarkus.runtime.LaunchMode;
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

    private String startedAt;

    @PostConstruct
    void init() {
        startedAt = Instant.now().toString();
    }

    public String getAppName() {
        return appName;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getStartedAt() {
        return startedAt;
    }

    /**
     * Package-private for unit testing — receives LaunchMode as arg
     * so tests can call resolveEnvironment(LaunchMode.DEVELOPMENT)
     * without needing to mock the static LaunchMode.current() call.
     */
    String resolveEnvironment(LaunchMode mode) {
        return switch (mode) {
            case DEVELOPMENT -> "dev";
            case TEST -> "test";
            case NORMAL -> "prod";
        };
    }

    /**
     * Production entry point — calls static LaunchMode.current()
     */
    public String getEnvironment() {
        return resolveEnvironment(LaunchMode.current());
    }

    public SystemInfo toInfo() {
        return new SystemInfo(appName, appVersion, getEnvironment(), startedAt);
    }

    public record SystemInfo(
            String name,
            String version,
            String environment,
            String startedAt
    ) {}
}
