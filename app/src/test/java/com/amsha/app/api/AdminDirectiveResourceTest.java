package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import com.amsha.selfevolve.api.DirectiveState;
import com.amsha.selfevolve.api.SynthesizedDirective;
import com.amsha.selfevolve.policy.InteractionPolicySynthesizer;
import com.amsha.selfevolve.policy.ShadowLifecycleManager;
import com.amsha.selfevolve.store.DirectiveStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminDirectiveResourceTest {

    @Mock
    DirectiveStorePort directiveStore;

    @Mock
    InteractionPolicySynthesizer synthesizer;

    @Mock
    ShadowLifecycleManager lifecycleManager;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    private AdminDirectiveResource resource;

    @BeforeEach
    void setUp() {
        resource = new AdminDirectiveResource();
        setField(resource, "directiveStore", directiveStore);
        setField(resource, "synthesizer", synthesizer);
        setField(resource, "lifecycleManager", lifecycleManager);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void testListDirectives() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("usr-1");
        when(directiveStore.getAll("usr-1", 20)).thenReturn(List.of());

        Response resp = resource.listDirectives(null);
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testGetDirectiveNotFoundAndFound() {
        when(directiveStore.getById("d-1")).thenReturn(null);
        Response r404 = resource.getDirective("d-1");
        assertEquals(404, r404.getStatus());

        SynthesizedDirective d = new SynthesizedDirective(
                "d-2",
                "usr-1",
                "rule 1",
                DirectiveState.ACTIVE,
                0.9,
                "src-1",
                "hash-1",
                0,
                0,
                Instant.now(),
                Instant.now());
        when(directiveStore.getById("d-2")).thenReturn(d);

        Response r200 = resource.getDirective("d-2");
        assertEquals(200, r200.getStatus());
    }

    @Test
    void testForbiddenEndpointsWithoutInternalRole() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        assertEquals(403, resource.disableDirective("d-1").getStatus());
        assertEquals(403, resource.deleteDirective("d-1").getStatus());
        assertEquals(403, resource.synthesizeDirectives().getStatus());
        assertEquals(403, resource.reconcileDirectives().getStatus());
    }

    @Test
    void testDisableDirectiveWithInternalRole() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        SynthesizedDirective d = new SynthesizedDirective(
                "d-3",
                "usr-1",
                "rule 1",
                DirectiveState.ACTIVE,
                0.9,
                "src-1",
                "hash-1",
                0,
                0,
                Instant.now(),
                Instant.now());
        when(directiveStore.getById("d-3")).thenReturn(d);
        when(lifecycleManager.disable(d)).thenReturn(d);

        Response resp = resource.disableDirective("d-3");
        assertEquals(200, resp.getStatus());
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
