package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.reasoning.prompt.PromptRegistry;
import com.amsha.persistence.api.PromptDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromptAdminResourceTest {

    @Mock
    PromptRegistry promptRegistry;

    @Mock
    SecurityContext securityContext;

    PromptAdminResource resource;

    private static final String PROMPT_ID = "prompt-123";

    @BeforeEach
    void setUp() {
        resource = new PromptAdminResource();
        setField(resource, "promptRegistry", promptRegistry);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void listPromptsReturns200() {
        when(promptRegistry.list()).thenReturn(List.of(samplePrompt()));

        Response resp = resource.listPrompts();

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getPromptReturns200WhenFound() {
        when(promptRegistry.getById(PROMPT_ID)).thenReturn(Optional.of(samplePrompt()));

        Response resp = resource.getPrompt(PROMPT_ID);

        assertEquals(200, resp.getStatus());
    }

    @Test
    void getPromptReturns404WhenNotFound() {
        when(promptRegistry.getById(PROMPT_ID)).thenReturn(Optional.empty());

        Response resp = resource.getPrompt(PROMPT_ID);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void updatePromptReturns200WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(promptRegistry.getById(PROMPT_ID)).thenReturn(Optional.of(samplePrompt()));

        PromptAdminResource.PromptUpdateRequest request =
                new PromptAdminResource.PromptUpdateRequest(null, null, "new content", null, null, null, null);
        Response resp = resource.updatePrompt(PROMPT_ID, request);

        assertEquals(200, resp.getStatus());
        verify(promptRegistry).save(any(PromptDto.class));
    }

    @Test
    void updatePromptReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        PromptAdminResource.PromptUpdateRequest request =
                new PromptAdminResource.PromptUpdateRequest(null, null, "content", null, null, null, null);
        Response resp = resource.updatePrompt(PROMPT_ID, request);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void updatePromptReturns404WhenNotFound() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(promptRegistry.getById(PROMPT_ID)).thenReturn(Optional.empty());

        PromptAdminResource.PromptUpdateRequest request =
                new PromptAdminResource.PromptUpdateRequest(null, null, "content", null, null, null, null);
        Response resp = resource.updatePrompt(PROMPT_ID, request);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void createPromptReturns201WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        PromptAdminResource.PromptUpdateRequest request = new PromptAdminResource.PromptUpdateRequest(
                "coordinator", "persona", "content", null, null, null, null);
        Response resp = resource.createPrompt(request);

        assertEquals(201, resp.getStatus());
        assertNotNull(resp.getLocation());
        verify(promptRegistry).save(any(PromptDto.class));
    }

    @Test
    void createPromptReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        PromptAdminResource.PromptUpdateRequest request = new PromptAdminResource.PromptUpdateRequest(
                "coordinator", "persona", "content", null, null, null, null);
        Response resp = resource.createPrompt(request);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void createPromptReturns400WhenMissingFields() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        // roleId is null
        PromptAdminResource.PromptUpdateRequest request =
                new PromptAdminResource.PromptUpdateRequest(null, "persona", "content", null, null, null, null);
        Response resp = resource.createPrompt(request);

        assertEquals(400, resp.getStatus());
    }

    @Test
    void deletePromptReturns204WhenDeleted() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(promptRegistry.delete(PROMPT_ID)).thenReturn(true);

        Response resp = resource.deletePrompt(PROMPT_ID);

        assertEquals(204, resp.getStatus());
    }

    @Test
    void deletePromptReturns404WhenNotFound() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(promptRegistry.delete(PROMPT_ID)).thenReturn(false);

        Response resp = resource.deletePrompt(PROMPT_ID);

        assertEquals(404, resp.getStatus());
    }

    @Test
    void deletePromptReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.deletePrompt(PROMPT_ID);

        assertEquals(403, resp.getStatus());
    }

    @Test
    void refreshCacheReturns200WhenInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp = resource.refreshCache();

        assertEquals(200, resp.getStatus());
        verify(promptRegistry).refresh();
    }

    @Test
    void refreshCacheReturns403WhenNotInternal() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.refreshCache();

        assertEquals(403, resp.getStatus());
    }

    private PromptDto samplePrompt() {
        return new PromptDto(
                PROMPT_ID,
                "coordinator",
                "persona",
                "You are a coordinator.",
                1,
                "ACTIVE",
                100,
                "{}",
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
