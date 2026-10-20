package com.amsha.app.security;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Startup;

/** Stops production-built artifacts from starting with a conflicting runtime profile. */
@IfBuildProfile("prod")
@Startup
@ApplicationScoped
public class BuildRuntimeProfileGuard {

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String runtimeProfile;

    @PostConstruct
    void validateProfile() {
        if ("prod".equalsIgnoreCase(runtimeProfile)) {
            return;
        }

        String message = "Amsha cannot start: this binary was built with the 'prod' profile but is configured "
                + "to run with '"
                + runtimeProfile
                + "'. Production startup security checks are skipped in dev/test profiles, and build-time settings "
                + "cannot be changed at runtime. Set QUARKUS_PROFILE=prod in dist/.env and restart, or rebuild "
                + "the binary for development.";
        System.err.println(message);
        throw new IllegalStateException(message);
    }
}
