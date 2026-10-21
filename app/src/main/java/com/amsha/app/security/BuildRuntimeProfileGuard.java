package com.amsha.app.security;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Startup;

/** Stops production-built artifacts from starting with a conflicting runtime profile. */
@IfBuildProfile(BuildRuntimeProfileGuard.PROD_PROFILE)
@Startup
@ApplicationScoped
public class BuildRuntimeProfileGuard {

    static final String PROD_PROFILE = "prod";
    private static final String FILESYSTEM_PROFILE = PROD_PROFILE + ",filesystem";
    private static final String POSTGRESQL_PROFILE = PROD_PROFILE + ",postgresql";
    private static final List<String> SUPPORTED_RUNTIME_PROFILES =
            List.of(PROD_PROFILE, FILESYSTEM_PROFILE, POSTGRESQL_PROFILE);

    @ConfigProperty(name = "quarkus.profile", defaultValue = PROD_PROFILE)
    String runtimeProfile;

    @PostConstruct
    void validateProfile() {
        if (runtimeProfile != null
                && SUPPORTED_RUNTIME_PROFILES.stream().anyMatch(profile -> profile.equalsIgnoreCase(runtimeProfile))) {
            return;
        }

        String message = "Amsha cannot start: this binary was built with the 'prod' profile but is configured "
                + "to run with '"
                + runtimeProfile
                + "'. Supported runtime profiles for this production build are: "
                + String.join(", ", SUPPORTED_RUNTIME_PROFILES)
                + ". Set QUARKUS_PROFILE to one of these values in dist/.env and restart, or rebuild "
                + "the binary for a different profile.";
        System.err.println(message);
        throw new IllegalStateException(message);
    }
}
