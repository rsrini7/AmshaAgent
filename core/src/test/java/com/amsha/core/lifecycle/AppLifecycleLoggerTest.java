package com.amsha.core.lifecycle;

import static org.mockito.Mockito.*;

import com.amsha.core.config.SystemMetadataRegistry;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.StartupEvent;

class AppLifecycleLoggerTest {

    @Test
    void testOnStart() {
        AppLifecycleLogger logger = new AppLifecycleLogger();
        logger.metadata = mock(SystemMetadataRegistry.class);
        when(logger.metadata.getAppName()).thenReturn("amsha");

        // Should not throw exception
        logger.onStart(new StartupEvent());

        verify(logger.metadata).getAppName();
    }
}
