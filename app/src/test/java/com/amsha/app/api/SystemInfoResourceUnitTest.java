package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;

import com.amsha.core.config.SystemMetadataRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemInfoResourceUnitTest {

    @Mock
    SystemMetadataRegistry registry;

    @InjectMocks
    SystemInfoResource resource;

    @Test
    void testGetInfo() {
        when(registry.toInfo()).thenReturn(new SystemMetadataRegistry.SystemInfo("amsha", "0.1.0", "prod", "now"));

        Response response = resource.getInfo();
        assertEquals(200, response.getStatus());
        assertNotNull(response.getEntity());
    }
}
