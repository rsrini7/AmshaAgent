package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BuildRuntimeProfileGuardTest {

    @Test
    void acceptsSupportedProductionProfiles() {
        assertDoesNotThrow(() -> validate("prod"));
        assertDoesNotThrow(() -> validate("prod,filesystem"));
        assertDoesNotThrow(() -> validate("prod,postgresql"));
    }

    @Test
    void rejectsDevelopmentAndUnknownProfileCombinations() {
        assertThrows(IllegalStateException.class, () -> validate("prod,dev"));
        assertThrows(IllegalStateException.class, () -> validate("prod,unknown"));
    }

    private static void validate(String runtimeProfile) {
        BuildRuntimeProfileGuard guard = new BuildRuntimeProfileGuard();
        guard.runtimeProfile = runtimeProfile;
        guard.validateProfile();
    }
}
