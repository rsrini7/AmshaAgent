package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

class DotenvConfigSourceProviderTest {

    @Test
    void testOrdinalIsExplicitly296() {
        DotenvConfigSourceProvider provider = new DotenvConfigSourceProvider();
        Iterable<ConfigSource> sources =
                provider.getConfigSources(Thread.currentThread().getContextClassLoader());

        ConfigSource source = sources.iterator().next();
        assertEquals(296, source.getOrdinal(), "Ordinal must be 296 to beat SmallRye's built-in .env reader at 295");
    }
}
