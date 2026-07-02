package com.amsha.persistence.sqlite.testutil;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile that explicitly enables Flyway migration.
 * Needed because the root {@code .env} sets {@code QUARKUS_FLYWAY_ENABLED=false}
 * which overrides the test {@code application.yaml} via DotenvConfigSource.
 */
public class FlywayEnabledTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.flyway.enabled", "true",
                "quarkus.flyway.migrate-at-start", "true");
    }
}
