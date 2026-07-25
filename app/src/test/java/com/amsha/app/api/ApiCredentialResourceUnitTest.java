package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

import com.amsha.persistence.api.IdentityStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiCredentialResourceUnitTest {

    @Mock
    IdentityStorePort identityStore;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    @InjectMocks
    ApiCredentialResource resource;

    @BeforeEach
    void setUp() {
        resource.securityContext = securityContext;
    }

    @Test
    void testCreateApiCredentialAnonymous() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("anonymous");

        Response response = resource.createApiCredential(null);
        assertEquals(401, response.getStatus());
    }

    @Test
    void testCreateApiCredentialEmptyRoles() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user1");

        IdentityResource.CreateApiCredentialRequest req = new IdentityResource.CreateApiCredentialRequest("", 3600L);
        Response response = resource.createApiCredential(req);
        assertEquals(400, response.getStatus());
    }
}
