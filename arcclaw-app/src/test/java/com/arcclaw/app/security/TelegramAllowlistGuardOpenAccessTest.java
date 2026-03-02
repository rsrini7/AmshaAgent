package com.arcclaw.app.security;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import com.arcclaw.core.security.TelegramAllowlistGuard;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TelegramAllowlistGuardOpenAccessTest {

    @Inject
    TelegramAllowlistGuard guard;

    @Test
    void testAllowlistOpenAccess() {
        // In the default application.yaml test profile, allowed-users is "" (empty)
        // This signifies open access.
        assertTrue(guard.isAllowed(null, "anyuser"));
        assertTrue(guard.isAllowed(null, null));
        assertTrue(guard.isAllowed(null, ""));
    }
}
