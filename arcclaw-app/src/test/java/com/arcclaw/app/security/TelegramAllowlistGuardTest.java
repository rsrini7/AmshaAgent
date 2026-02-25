package com.arcclaw.app.security;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TelegramAllowlistGuardTest {

    @Inject
    TelegramAllowlistGuard guard;

    @Test
    void testAllowlistOpenAccess() {
        // In the default application.yaml test profile, allowed-users is "" (empty)
        // This signifies open access
        assertTrue(guard.isAllowed("anyuser"));
        assertTrue(guard.isAllowed(null));
    }
    
    @Test
    void testAllowlistLogicWithProgrammaticValues() {
        // Since we cannot easily change config at runtime, we'll test the logic directly using a new instance
        TelegramAllowlistGuard manualGuard = new TelegramAllowlistGuard();
        manualGuard.allowedUsers = java.util.Optional.of(java.util.List.of("Alice", "@Bob", "charlie"));
        
        // Valid users
        assertTrue(manualGuard.isAllowed("Alice"));
        assertTrue(manualGuard.isAllowed("@Alice")); // user provides leading @
        assertTrue(manualGuard.isAllowed("Bob"));    // configured with @, user provides without
        assertTrue(manualGuard.isAllowed("@Bob"));   
        assertTrue(manualGuard.isAllowed("CHARLIE")); // case insensitive
        
        // Invalid users
        assertFalse(manualGuard.isAllowed("eve"));
        assertFalse(manualGuard.isAllowed(null));
        assertFalse(manualGuard.isAllowed(""));
    }
}
