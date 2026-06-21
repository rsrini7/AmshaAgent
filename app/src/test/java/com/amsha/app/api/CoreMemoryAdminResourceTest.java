package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.memory.tiered.CoreMemoryService;
import com.amsha.persistence.api.CoreMemoryBlockDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoreMemoryAdminResourceTest {

    @Mock
    CoreMemoryService coreMemoryService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    CoreMemoryAdminResource resource;

    private static final String USER_ID = "test-user";
    private static final String BLOCK_KEY = "user_profile";

    @BeforeEach
    void setUp() {
        resource = new CoreMemoryAdminResource();
        setField(resource, "coreMemoryService", coreMemoryService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void listBlocksReturns200() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(coreMemoryService.readAll(USER_ID)).thenReturn(List.of(sampleBlock()));

        Response resp = resource.listBlocks();

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getBlockReturns200WhenFound() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(coreMemoryService.get(USER_ID, BLOCK_KEY)).thenReturn(Optional.of(sampleBlock()));

        Response resp = resource.getBlock(BLOCK_KEY);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getBlockReturns404WhenNotFound() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(coreMemoryService.get(USER_ID, BLOCK_KEY)).thenReturn(Optional.empty());

        Response resp = resource.getBlock(BLOCK_KEY);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void upsertBlockReturns200WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        CoreMemoryAdminResource.CoreMemoryUpdateRequest req =
                new CoreMemoryAdminResource.CoreMemoryUpdateRequest("new content");
        Response resp = resource.upsertBlock(BLOCK_KEY, req);

        assertEquals(200, resp.getStatus());
        verify(coreMemoryService).upsert(USER_ID, BLOCK_KEY, "new content");
    }

    @Test
    void upsertBlockReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        CoreMemoryAdminResource.CoreMemoryUpdateRequest req =
                new CoreMemoryAdminResource.CoreMemoryUpdateRequest("content");
        Response resp = resource.upsertBlock(BLOCK_KEY, req);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void upsertBlockReturns400ForInvalidBlockKey() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        doThrow(new IllegalArgumentException("Unknown block key: bad_key"))
                .when(coreMemoryService)
                .upsert(eq(USER_ID), eq("bad_key"), anyString());

        CoreMemoryAdminResource.CoreMemoryUpdateRequest req =
                new CoreMemoryAdminResource.CoreMemoryUpdateRequest("content");
        Response resp = resource.upsertBlock("bad_key", req);

        assertEquals(400, resp.getStatus());
    }

    @Test
    void deleteBlockReturns204WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        Response resp = resource.deleteBlock(BLOCK_KEY);

        assertEquals(204, resp.getStatus());
        verify(coreMemoryService).delete(USER_ID, BLOCK_KEY);
    }

    @Test
    void deleteBlockReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.deleteBlock(BLOCK_KEY);

        assertEquals(403, resp.getStatus());
    }

    private CoreMemoryBlockDto sampleBlock() {
        return new CoreMemoryBlockDto("block-1", USER_ID, BLOCK_KEY, "content", 1, Instant.now(), Instant.now());
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
