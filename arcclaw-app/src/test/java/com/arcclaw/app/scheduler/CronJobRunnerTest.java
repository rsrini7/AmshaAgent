package com.arcclaw.app.scheduler;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arcclaw.core.security.DevSecretSeeder;

class CronJobRunnerTest {

    @Test
    @DisplayName("Verify that CronJobRunner has no DevSecretSeeder injection field (B1 Security Hardening)")
    void testDevSecretSeederNotInvocable() {
        Field[] fields = CronJobRunner.class.getDeclaredFields();
        
        boolean hasDevSeeder = Arrays.stream(fields)
                .anyMatch(field -> field.getType().equals(DevSecretSeeder.class));
        
        assertTrue(!hasDevSeeder, "CronJobRunner must NOT inject DevSecretSeeder directly. " +
                "Seeding is managed via DevSecretSeederStartup (@IfBuildProfile).");
    }
}
