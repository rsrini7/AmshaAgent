package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotenvConfigSourceProviderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetSystemProperties() {
        System.clearProperty("amsha.dotenv.enabled");
    }

    @Test
    void testOrdinalIsExplicitly296WhenEnabled() {
        System.setProperty("amsha.dotenv.enabled", "true");
        DotenvConfigSourceProvider provider = new DotenvConfigSourceProvider();
        Iterable<ConfigSource> sources =
                provider.getConfigSources(Thread.currentThread().getContextClassLoader());

        ConfigSource source = sources.iterator().next();
        assertEquals(296, source.getOrdinal(), "Ordinal must be 296 to beat SmallRye's built-in .env reader at 295");
    }

    @Test
    void testDisabledWhenPropertySetToFalse() {
        System.setProperty("amsha.dotenv.enabled", "false");
        DotenvConfigSourceProvider provider = new DotenvConfigSourceProvider();
        Iterable<ConfigSource> sources =
                provider.getConfigSources(Thread.currentThread().getContextClassLoader());

        ConfigSource source = sources.iterator().next();
        assertTrue(
                source.getProperties().isEmpty(), "Properties must be empty when amsha.dotenv.enabled is set to false");
    }

    @Test
    void resolvesParentEnvForDevWorkingDirectory() throws IOException {
        Path appDirectory = Files.createDirectory(tempDir.resolve("app"));
        Path rootEnv = tempDir.resolve(".env");
        Files.writeString(rootEnv, "AMSHA_TTS_ENABLED=true\n");

        assertEquals(rootEnv, DotenvConfigSourceProvider.resolveEnvPath(appDirectory));
    }

    @Test
    void prefersLocalEnvOverParentEnv() throws IOException {
        Path appDirectory = Files.createDirectory(tempDir.resolve("app"));
        Path localEnv = appDirectory.resolve(".env");
        Files.writeString(tempDir.resolve(".env"), "AMSHA_TTS_ENABLED=false\n");
        Files.writeString(localEnv, "AMSHA_TTS_ENABLED=true\n");

        assertEquals(localEnv, DotenvConfigSourceProvider.resolveEnvPath(appDirectory));
    }
}
