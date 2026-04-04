package com.arcclaw.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryBootstrapTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("KEY_A");
        System.clearProperty("KEY_B");
        System.clearProperty("KEY_C");
        System.clearProperty("KEY_D");
        System.clearProperty("KEY_E");
    }

    @Test
    void testLoadDotEnv_setsSystemProperties(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of(
            "KEY_A=hello",
            "KEY_B=world",
            "# comment line",
            "KEY_C=value with spaces",
            "KEY_D=value #inline comment",
            "KEY_E=\"quoted#value\""
        ));

        BinaryBootstrap.loadDotEnv(tempDir);

        assertEquals("hello", System.getProperty("KEY_A"));
        assertEquals("world", System.getProperty("KEY_B"));
        assertEquals("value with spaces", System.getProperty("KEY_C"));
        assertEquals("value", System.getProperty("KEY_D"));
        assertEquals("\"quoted#value\"", System.getProperty("KEY_E"));
    }

    @Test
    void testLoadDotEnv_doesNotOverwriteExistingEnv(@TempDir Path tempDir) throws IOException {
        System.setProperty("KEY_A", "original");
        Path envFile = tempDir.resolve(".env");
        Files.write(envFile, List.of("KEY_A=overridden"));

        BinaryBootstrap.loadDotEnv(tempDir);

        assertEquals("original", System.getProperty("KEY_A"));
    }

    @Test
    void testLoadDotEnv_missingFile_doesNotThrow() {
        BinaryBootstrap.loadDotEnv(Path.of("/nonexistent/path/at/all/ever"));
    }
}
