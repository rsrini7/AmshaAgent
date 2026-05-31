package com.arcclaw.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;

import java.util.List;

import com.arcclaw.core.tools.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolRegistryResourceTest {

    @Mock
    ToolRegistry toolRegistry;

    ToolRegistryResource resource;

    @BeforeEach
    void setUp() {
        resource = new ToolRegistryResource();
        setField(resource, "toolRegistry", toolRegistry);
    }

    @Test
    void listToolsReturns200() {
        ToolRegistry.ToolDescriptor descriptor = new ToolRegistry.ToolDescriptor("sandbox_exec", "Execute code", "{}");
        when(toolRegistry.listToolDescriptors()).thenReturn(List.of(descriptor));

        Response resp = resource.listTools();

        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ToolRegistry.ToolDescriptor> body = (List<ToolRegistry.ToolDescriptor>) resp.getEntity();
        assertEquals(1, body.size());
        assertEquals("sandbox_exec", body.get(0).name());
    }

    @Test
    void listToolsReturns200WithEmptyList() {
        when(toolRegistry.listToolDescriptors()).thenReturn(List.of());

        Response resp = resource.listTools();

        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ToolRegistry.ToolDescriptor> body = (List<ToolRegistry.ToolDescriptor>) resp.getEntity();
        assertTrue(body.isEmpty());
    }

    @Test
    void listToolsReturns200WithMultipleTools() {
        ToolRegistry.ToolDescriptor d1 = new ToolRegistry.ToolDescriptor("sandbox_exec", "Execute code", "{}");
        ToolRegistry.ToolDescriptor d2 = new ToolRegistry.ToolDescriptor("github", "GitHub operations", "{}");
        when(toolRegistry.listToolDescriptors()).thenReturn(List.of(d1, d2));

        Response resp = resource.listTools();

        assertEquals(200, resp.getStatus());
        @SuppressWarnings("unchecked")
        List<ToolRegistry.ToolDescriptor> body = (List<ToolRegistry.ToolDescriptor>) resp.getEntity();
        assertEquals(2, body.size());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
