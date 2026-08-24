package com.amsha.security.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Vocabulary contracts for the security-api module: vault-key zeroization, tool-operation
 * risk mapping, and HITL risk levels. Guards the S4 extraction surface.
 */
public class SecurityApiVocabularyTest {

    @Test
    void vaultKeyWrapsMaterialAndZeroizesOnClose() {
        byte[] material = {1, 2, 3, 4};
        VaultKey key = new VaultKey(material);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, key.get());

        key.close();
        assertArrayEquals(new byte[] {0, 0, 0, 0}, material);
        assertThrows(IllegalStateException.class, key::get);
    }

    @Test
    void vaultKeyRejectsNullMaterial() {
        assertThrows(IllegalArgumentException.class, () -> new VaultKey(null));
    }

    @Test
    void vaultKeyToStringNeverLeaksSecrets() {
        try (VaultKey key = new VaultKey("super-secret".getBytes())) {
            assertFalse(key.toString().contains("super-secret"));
        }
    }

    @Test
    void toolOperationCarriesRiskLevel() {
        assertEquals(RiskLevel.LOW, ToolOperation.READ_FS.riskLevel());
        assertEquals(RiskLevel.HIGH, ToolOperation.WRITE_FS.riskLevel());
        assertEquals(RiskLevel.CRITICAL, ToolOperation.EXECUTE_SHELL.riskLevel());
        assertEquals(RiskLevel.MEDIUM, ToolOperation.NETWORK_OUTBOUND.riskLevel());
        for (ToolOperation op : ToolOperation.values()) {
            assertTrue(op.riskLevel() != null, op.name() + " must declare a risk level");
        }
    }

    @Test
    void riskLevelsAreOrderedBySeverityName() {
        assertEquals(4, RiskLevel.values().length);
        assertEquals("LOW", RiskLevel.LOW.name());
        assertEquals("CRITICAL", RiskLevel.CRITICAL.name());
    }

    @Test
    void autonomyLevelValuesAreStable() {
        assertEquals(4, AutonomyLevel.values().length);
        for (AutonomyLevel level : AutonomyLevel.values()) {
            assertFalse(level.name().isBlank());
        }
    }

    @Test
    void outboundPurposeValuesAreStable() {
        for (OutboundPurpose purpose : OutboundPurpose.values()) {
            assertFalse(purpose.name().isBlank());
        }
    }

    @Test
    void outboundPolicyDecisionExposesOutcomeVocabulary() {
        var allow = OutboundPolicyDecision.allow(OutboundPurpose.PROVIDER_API_CALL, "user-1");
        var deny = OutboundPolicyDecision.deny("not permitted", OutboundPurpose.PROVIDER_API_CALL, "user-1");
        var hitl = OutboundPolicyDecision.requireHitl("needs approval", OutboundPurpose.PROVIDER_API_CALL, "user-1");

        assertTrue(allow.isAllowed() && !allow.isDenied() && !allow.requiresHitl());
        assertTrue(deny.isDenied() && deny.reason().orElse("").contains("not permitted"));
        assertTrue(hitl.requiresHitl());
        assertEquals(3, OutboundPolicyDecision.Decision.values().length);
    }

    @Test
    void requiresSecretAnnotationIsRepeatableWithVaultKeyAndEnvVar() throws Exception {
        var method = SecurityApiVocabularyTest.class.getDeclaredMethod("annotatedTool");
        var annotation = method.getAnnotation(RequiresSecrets.class);
        assertEquals(2, annotation.value().length);
        assertEquals("api_key", annotation.value()[0].vaultKey());
        assertEquals("API_KEY", annotation.value()[0].envVar());
        assertEquals("db_pass", annotation.value()[1].vaultKey());
        assertEquals("DB_PASS", annotation.value()[1].envVar());
    }

    @RequiresSecret(vaultKey = "api_key", envVar = "API_KEY")
    @RequiresSecret(vaultKey = "db_pass", envVar = "DB_PASS")
    @SuppressWarnings("unused")
    private void annotatedTool() {}

    @Test
    void cryptoConstantsPinHmacAlgorithm() {
        assertEquals("HmacSHA256", CryptoConstants.HMAC_SHA256);
    }
}
