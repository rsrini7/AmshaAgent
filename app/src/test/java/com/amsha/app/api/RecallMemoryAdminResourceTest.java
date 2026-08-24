package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import com.amsha.memory.tiered.RecallMemoryService;
import com.amsha.persistence.api.RecallMessageDto;
import com.amsha.persistence.api.RecallSearchQuery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecallMemoryAdminResourceTest {

    @Mock
    RecallMemoryService recallService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    RecallMemoryAdminResource resource;

    private static final String USER_ID = "test-user";

    @BeforeEach
    void setUp() {
        resource = new RecallMemoryAdminResource();
        setField(resource, "recallService", recallService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void searchReturns200() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(recallService.search(any(RecallSearchQuery.class))).thenReturn(List.of(sampleMessage()));

        Response resp = resource.search("test", null, 10, false);

        assertEquals(200, resp.getStatus());
        verify(recallService).search(any(RecallSearchQuery.class));
    }

    @Test
    void searchWithSessionIdReturns200() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);
        when(recallService.search(any(RecallSearchQuery.class))).thenReturn(List.of(sampleMessage()));

        Response resp = resource.search("test", "session-123", 10, false);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void purgeReturns200WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        Response resp = resource.purgeExpired(30);

        assertEquals(200, resp.getStatus());
        verify(recallService).purgeExpired(USER_ID, 30);
    }

    @Test
    void purgeReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.purgeExpired(30);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void purgeWithDefaultRetentionDays() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        Response resp = resource.purgeExpired(null);

        assertEquals(200, resp.getStatus());
        verify(recallService).purgeExpired(USER_ID, 30);
    }

    private RecallMessageDto sampleMessage() {
        return new RecallMessageDto(
                "msg-1",
                "session-1",
                USER_ID,
                1,
                "hash-1",
                "user",
                "USER_MESSAGE",
                "Hello, world!",
                null,
                null,
                false,
                false,
                Instant.now(),
                null);
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
