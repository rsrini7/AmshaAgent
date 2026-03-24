package com.arcclaw.app.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.security.SecretVault;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

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
        assertTrue(vault.systemKeyExists("arcclaw-audit-hmac-key"), 
            "Audit HMAC key should be automatically seeded in test profile");
    }
    @Test
    void testDevSeederNotAccessibleInTestProfile() {
        assertFalse(Arc.container().instance(DevSecretSeederStartup.class).isAvailable(),
            "DevSecretSeederStartup should not be accessible in the test profile");
    }
}
