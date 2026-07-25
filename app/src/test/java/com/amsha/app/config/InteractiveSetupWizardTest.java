package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InteractiveSetupWizardTest {

    private InputStream originalIn;

    @BeforeEach
    void setUp() {
        originalIn = System.in;
    }

    @AfterEach
    void tearDown() {
        System.setIn(originalIn);
    }

    @Test
    void testRunWithSimulatedInput(@TempDir Path tempDir) throws Exception {
        // Inputs: 1 (groq), y (unified), my-api-key, n (telegram), n (github), n (serper), n (brave), enter for pepper
        String simulatedInput = "1\ny\nmy-api-key\nn\nn\nn\nn\n\n";
        System.setIn(new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8)));

        System.setProperty("AMSHA_LLM_PROVIDER", "groq");

        InteractiveSetupWizard.run(tempDir, new String[0]);

        Path envFile = tempDir.resolve(".env");
        assertTrue(Files.exists(envFile));
    }
}
