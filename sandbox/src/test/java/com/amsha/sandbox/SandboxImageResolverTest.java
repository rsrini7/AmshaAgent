package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class SandboxImageResolverTest {

    @Test
    void testPrecedence() {
        Function<String, String> noEnv = _ -> null;

        // Level 5: Default fallback when everything is empty
        assertEquals(
                "localhost/amsha-tool-runner:latest",
                SandboxImageResolver.resolveImage(noEnv, Optional.empty(), Optional.empty()));

        // Level 4: Podman config image when higher levels are empty
        assertEquals(
                "custom/podman:2.0",
                SandboxImageResolver.resolveImage(noEnv, Optional.empty(), Optional.of("custom/podman:2.0")));

        // Level 3: AMSHA_SANDBOX_PODMAN_IMAGE env var over podman config image
        Function<String, String> env2 = k -> "AMSHA_SANDBOX_PODMAN_IMAGE".equals(k) ? "custom/env2:3.0" : null;
        assertEquals(
                "custom/env2:3.0",
                SandboxImageResolver.resolveImage(env2, Optional.empty(), Optional.of("custom/podman:2.0")));

        // Level 2: Primary config image over AMSHA_SANDBOX_PODMAN_IMAGE and podman config
        assertEquals(
                "custom/primary:1.0",
                SandboxImageResolver.resolveImage(
                        env2, Optional.of("custom/primary:1.0"), Optional.of("custom/podman:2.0")));

        // Level 1: AMSHA_SANDBOX_IMAGE env var takes absolute precedence over all
        Function<String, String> env1 = Map.of(
                "AMSHA_SANDBOX_IMAGE", "custom/env1:0.1",
                "AMSHA_SANDBOX_PODMAN_IMAGE", "custom/env2:3.0")::get;
        assertEquals(
                "custom/env1:0.1",
                SandboxImageResolver.resolveImage(
                        env1, Optional.of("custom/primary:1.0"), Optional.of("custom/podman:2.0")));
    }

    @Test
    void testBlankValuesSkipped() {
        Function<String, String> blankEnv = _ -> "   ";

        // Blank env and blank primary config should fall through to valid podman config
        assertEquals(
                "custom/podman:2.0",
                SandboxImageResolver.resolveImage(blankEnv, Optional.of("  "), Optional.of("custom/podman:2.0")));

        // Blank at all levels falls through to default
        assertEquals(
                "localhost/amsha-tool-runner:latest",
                SandboxImageResolver.resolveImage(blankEnv, Optional.of(" "), Optional.of("  ")));
    }

    @Test
    void testConvenienceMethods() {
        String direct = SandboxImageResolver.resolveImage();
        assertNotNull(direct);
        assertFalse(direct.isBlank());

        String withParams = SandboxImageResolver.resolveImage(Optional.empty(), Optional.empty());
        assertNotNull(withParams);
        assertFalse(withParams.isBlank());
    }
}
