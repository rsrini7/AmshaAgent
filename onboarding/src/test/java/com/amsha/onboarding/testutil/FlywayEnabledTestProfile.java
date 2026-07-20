package com.amsha.onboarding.testutil;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile that explicitly enables Flyway migration.
 * Needed because the root {@code .env} sets {@code QUARKUS_FLYWAY_ENABLED=false}
 * which overrides the test {@code application.yaml} via DotenvConfigSource.
 */
public class FlywayEnabledTestProfile implements QuarkusTestProfile {

    static {
        System.setProperty("AMSHA_LLM_API_KEY", "");
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.flyway.enabled", "true",
                "quarkus.flyway.migrate-at-start", "true",
                "amsha.ui.skip-onboarding", "false",
                "amsha.llm.provider", "mock-provider");
    }
}
