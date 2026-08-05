package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveSetupWizardOptOutTest {
    private final InputStream originalInput = System.in;

    @AfterEach
    void restoreInputAndProperties() {
        System.setIn(originalInput);
        System.clearProperty("AMSHA_TOOLS_BRAVE_API_KEY");
    }

    @Test
    void decliningOptionalModulePersistsExplicitOptOut(@TempDir Path tempDir) throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "# AMSHA_TOOLS_BRAVE_API_KEY=old-placeholder\n");
        System.setIn(new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)));

        Method method = InteractiveSetupWizard.class.getDeclaredMethod(
                "promptOptionalModule", Path.class, String.class, String.class);
        method.setAccessible(true);
        try {
            assertFalse(
                    (Boolean) method.invoke(null, envFile, "AMSHA_TOOLS_BRAVE_API_KEY", "Brave Search Integration"));
        } finally {
            method.setAccessible(false);
        }

        assertTrue(Files.readString(envFile).contains("AMSHA_TOOLS_BRAVE_API_KEY=none"));
    }
}
