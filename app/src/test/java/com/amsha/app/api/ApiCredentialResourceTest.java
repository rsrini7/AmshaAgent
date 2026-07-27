package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiCredentialResourceTest {

    @Mock
    IdentityStorePort identityStore;

    @Mock
    ApiCredentialStorePort apiCredentialStore;

    @Mock
    ApiKeyRegistry apiKeyRegistry;

    @Mock
    AuditService auditService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    private ApiCredentialResource resource;

    private static final String USER_ID = "usr-1";

    @BeforeEach
    void setUp() {
        resource = new ApiCredentialResource();
        setField(resource, "identityStore", identityStore);
        setField(resource, "apiCredentialStore", apiCredentialStore);
        setField(resource, "apiKeyRegistry", apiKeyRegistry);
        setField(resource, "auditService", auditService);
        setField(resource, "securityContext", securityContext);
    }

    @Test
    void testCreateApiCredentialUnauthorized() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("anonymous");

        Response resp = resource.createApiCredential(new IdentityResource.CreateApiCredentialRequest("MEMBER", 3600L));
        assertEquals(401, resp.getStatus());
    }

    @Test
    void testCreateApiCredentialSuccess() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-1", USER_ID, "MEMBER", "ACTIVE", 0, Instant.now(), Instant.now());
        when(identityStore.listMembershipsByUserId(USER_ID)).thenReturn(List.of(mem));
        when(apiKeyRegistry.computeDigest(anyString())).thenReturn("digest-1");
        when(apiCredentialStore.save(any())).thenReturn(true);

        Response resp = resource.createApiCredential(new IdentityResource.CreateApiCredentialRequest("MEMBER", 3600L));
        assertEquals(200, resp.getStatus());
        verify(apiKeyRegistry).register(eq(USER_ID), anyString());
    }

    @Test
    void testRevokeApiCredentialSuccess() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        ApiCredentialDto cred = new ApiCredentialDto(
                "cred-1", USER_ID, "digest-1", "MEMBER", 0, null, null, Instant.now(), Instant.now());
        when(apiCredentialStore.findById("cred-1")).thenReturn(Optional.of(cred));

        Response resp = resource.revokeApiCredential("cred-1");
        assertEquals(200, resp.getStatus());
        verify(apiKeyRegistry).unregisterByDigest("digest-1");
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
