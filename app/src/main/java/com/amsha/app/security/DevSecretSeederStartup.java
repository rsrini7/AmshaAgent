package com.amsha.app.security;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.amsha.security.DevSecretSeeder;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;

/**
 * Automatically seeds development secrets when the application starts
 * ONLY in the "dev" build profile.
 * <p>
 * This is a security gate - @IfBuildProfile ensures this bean (and its call
 * to DevSecretSeeder) is completely absent from production, staging, and
 * test builds, even if the runtime configuration flag is set.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevSecretSeederStartup {

    @Inject
    DevSecretSeeder seeder;

    /**
     * Seeds dev secrets after the core seeder has initialized itself.
     * Core seeder uses @Priority(100), so we use 150 to ensure it's ready.
     */
    void onStart(@Observes @Priority(150) StartupEvent ev) {
        seeder.seedDevSecrets();
    }
}
