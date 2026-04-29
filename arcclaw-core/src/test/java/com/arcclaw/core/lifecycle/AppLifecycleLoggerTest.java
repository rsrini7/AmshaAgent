package com.arcclaw.core.lifecycle;

import static org.mockito.Mockito.*;

import com.arcclaw.core.config.SystemMetadataRegistry;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

class AppLifecycleLoggerTest {

    @Test
    void testOnStart() {
        AppLifecycleLogger logger = new AppLifecycleLogger();
        logger.metadata = mock(SystemMetadataRegistry.class);
        when(logger.metadata.getAppName()).thenReturn("arcclaw");

        // Should not throw exception
        logger.onStart(new StartupEvent());
        
        verify(logger.metadata).getAppName();
    }
}
