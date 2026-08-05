package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DotenvConfigSourceProviderTest {

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
}
