package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import com.amsha.core.audit.AuditService;
import com.amsha.security.api.SecretVault;

import org.junit.jupiter.api.Test;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class DevSecretSeederTest {

    @Inject
    SecretVault vault;

    @Inject
    AuditService auditService;

    @Test
    void testAuditHmacKeySeededAtStartup() {
        // Wait for background audit writes if any
        auditService.waitForIdle();

        // In the 'test' profile, DevSecretSeeder.onStart should have seeded this.
        assertTrue(
                vault.systemKeyExists("amsha-audit-hmac-key"),
                "Audit HMAC key should be automatically seeded in test profile");
    }

    @Test
    void testDevSeederNotAccessibleInTestProfile() {
        assertFalse(
                Arc.container().instance(DevSecretSeederStartup.class).isAvailable(),
                "DevSecretSeederStartup should not be accessible in the test profile");
    }
}
