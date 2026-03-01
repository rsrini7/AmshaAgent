package com.arcclaw.app.security;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupSecurityValidatorTest {

    private StartupSecurityValidator validator;

    @BeforeEach
    void setup() {
        validator = new StartupSecurityValidator();
    }

    private void injectFields(String profile, String pepper, String hmacKey) throws Exception {
        setField("profile", profile);
        setField("pepper", pepper);
        setField("hmacKey", hmacKey);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = StartupSecurityValidator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(validator, value);
    }

    @Test
    void testProdProfile_devPepper_throws() throws Exception {
        injectFields("prod", "dev-pepper-not-for-production-xxx", "valid-prod-hmac-key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
        assertEquals("[SECURITY] arcclaw.vault.pepper is using the dev default. Set ARCCLAW_VAULT_PEPPER in your .env before running in prod.", ex.getMessage());
    }

    @Test
    void testProdProfile_devHmacKey_throws() throws Exception {
        injectFields("prod", "prod-secure-pepper-xxxxxxxxxxxxx", "dev-audit-hmac-key-min-32-bytes-00000");
        
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
        assertEquals("[SECURITY] arcclaw.audit.hmac-key is using the dev default. Set ARCCLAW_AUDIT_HMAC_KEY in your .env before running in prod.", ex.getMessage());
    }

    @Test
    void testProdProfile_bothDevDefaults_throwsPepperFirst() throws Exception {
        injectFields("prod", "dev-pepper-xxx", "dev-audit-hmac-key-xxx");
        
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
        assertEquals("[SECURITY] arcclaw.vault.pepper is using the dev default. Set ARCCLAW_VAULT_PEPPER in your .env before running in prod.", ex.getMessage());
    }

    @Test
    void testStagingProfile_devPepper_throws() throws Exception {
        injectFields("staging", "dev-pepper-xxx", "valid-staging-hmac-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        
        assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testStagingProfile_devHmacKey_throws() throws Exception {
        injectFields("staging", "valid-staging-pepper-xxxxxxxxxxxx", "dev-audit-hmac-key-min-32-bytes-00000");
        
        assertThrows(IllegalStateException.class, () -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testTestProfile_devPepper_doesNotThrow() throws Exception {
        injectFields("test", "dev-pepper-not-for-production-0000", "dev-audit-hmac-key-min-32-bytes-00000");
        
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testDevProfile_devSecrets_doesNotThrow() throws Exception {
        injectFields("dev", "dev-pepper-not-for-production-0000", "dev-audit-hmac-key-min-32-bytes-00000");
        
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }

    @Test
    void testProdProfile_validSecrets_doesNotThrow() throws Exception {
        injectFields("prod", 
            "prod-secure-pepper-000000000000000000000000000000", 
            "prod-secure-hmac-key-000000000000000000000000000000");
        // Phase 46: inject allowlist fields so the new allowlist check passes
        setField("allowedUserIds", Optional.of(List.of(123L)));
        setField("allowedUsers", Optional.empty());
        
        assertDoesNotThrow(() -> validator.onStart(new StartupEvent()));
    }
}
