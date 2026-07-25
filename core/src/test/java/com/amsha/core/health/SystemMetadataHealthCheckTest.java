package com.amsha.core.health;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.amsha.core.config.SystemMetadataRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemMetadataHealthCheckTest {

    @Mock
    SystemMetadataRegistry registry;

    SystemMetadataHealthCheck check;

    @BeforeEach
    void setUp() throws Exception {
        check = new SystemMetadataHealthCheck();
        var field = SystemMetadataHealthCheck.class.getDeclaredField("registry");
        field.setAccessible(true);
        field.set(check, registry);
    }

    @Test
    void upWhenBothVersionAndStartedAtAreSet() {
        when(registry.getAppVersion()).thenReturn("0.1.0");
        when(registry.getStartedAt()).thenReturn("2026-07-25T10:00:00Z");
        var response = check.call();
        assertTrue(response.getStatus().toString().equalsIgnoreCase("UP"));
    }

    @Test
    void downWhenVersionIsNull() {
        when(registry.getAppVersion()).thenReturn(null);
        when(registry.getStartedAt()).thenReturn("2026-07-25T10:00:00Z");
        var response = check.call();
        assertTrue(response.getStatus().toString().equalsIgnoreCase("DOWN"));
    }

    @Test
    void downWhenStartedAtIsNull() {
        when(registry.getAppVersion()).thenReturn("0.1.0");
        when(registry.getStartedAt()).thenReturn(null);
        var response = check.call();
        assertTrue(response.getStatus().toString().equalsIgnoreCase("DOWN"));
    }
}
