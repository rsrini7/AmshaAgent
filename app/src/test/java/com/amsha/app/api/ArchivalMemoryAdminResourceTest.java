package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.memory.tiered.ArchivalMemoryService;
import com.amsha.persistence.api.ArchivalChunkDto;
import com.amsha.persistence.api.ArchivalSearchQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArchivalMemoryAdminResourceTest {

    @Mock
    ArchivalMemoryService archivalService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    ArchivalMemoryAdminResource resource;

    private static final String USER_ID = "test-user";
    private static final String CHUNK_ID = "chunk-1";

    @BeforeEach
    void setUp() {
        resource = new ArchivalMemoryAdminResource();
        setField(resource, "archivalService", archivalService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void searchReturns200() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(archivalService.search(any(ArchivalSearchQuery.class))).thenReturn(List.of(sampleChunk()));

        Response resp = resource.search("test", 10, null);

        assertEquals(200, resp.getStatus());
        verify(archivalService).search(any(ArchivalSearchQuery.class));
    }

    @Test
    void searchWithChunkTypesReturns200() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(archivalService.search(any(ArchivalSearchQuery.class))).thenReturn(List.of(sampleChunk()));

        Response resp = resource.search("test", 10, "CODE,DOC");

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getByIdReturns200WhenFound() {
        when(archivalService.getById(CHUNK_ID)).thenReturn(Optional.of(sampleChunk()));

        Response resp = resource.getById(CHUNK_ID);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getByIdReturns404WhenNotFound() {
        when(archivalService.getById(CHUNK_ID)).thenReturn(Optional.empty());

        Response resp = resource.getById(CHUNK_ID);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void deleteReturns204WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp = resource.delete(CHUNK_ID);

        assertEquals(204, resp.getStatus());
        verify(archivalService).delete(CHUNK_ID);
    }

    @Test
    void deleteReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.delete(CHUNK_ID);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void evictReturns200WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp = resource.evictLowConfidence(0.5, 30);

        assertEquals(200, resp.getStatus());
        verify(archivalService).evictLowConfidence(0.5, 30);
    }

    @Test
    void evictReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.evictLowConfidence(0.5, 30);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void evictWithDefaultParams() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp = resource.evictLowConfidence(null, null);

        assertEquals(200, resp.getStatus());
        verify(archivalService).evictLowConfidence(0.5, 30);
    }

    private ArchivalChunkDto sampleChunk() {
        return new ArchivalChunkDto(
                CHUNK_ID,
                USER_ID,
                "Sample content",
                "CODE",
                "session-1",
                "msg-1",
                "java,spring",
                0.85,
                5,
                Instant.now(),
                Instant.now());
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
