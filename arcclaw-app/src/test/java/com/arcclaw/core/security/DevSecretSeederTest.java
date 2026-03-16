package com.arcclaw.core.security;

import com.arcclaw.core.audit.AuditService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
