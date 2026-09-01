package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthResourceUnitTest {

    @Mock
    Instance<ExternalIdentityProvider> providers;

    @Mock
    ExternalIdentityProvider provider1;

    @Mock
    IdentityStorePort identityStore;

    @Mock
    WebSessionStorePort webSessionStore;

    @Mock
    IdentityInvitationStorePort invitationStore;

    @Mock
    ApiKeyRegistry apiKeyRegistry;

    @Mock
    AuditService auditService;

    @Mock
    HttpHeaders httpHeaders;

    @Mock
    UriInfo uriInfo;

    @InjectMocks
    AuthResource resource;

    @BeforeEach
    void setUp() {
        resource.httpHeaders = httpHeaders;
        resource.uriInfo = uriInfo;
        resource.idleTimeoutMinutes = 720;
        lenient().when(httpHeaders.getCookies()).thenReturn(Collections.emptyMap());
        lenient().when(provider1.isEnabled()).thenReturn(true);
        lenient().when(provider1.getProviderId()).thenReturn("oidc");
    }

    @Test
    void testListProviders() {
        when(providers.stream()).thenReturn(List.of(provider1).stream());

        Response response = resource.listProviders();
        assertEquals(200, response.getStatus());
        List<?> list = (List<?>) response.getEntity();
        assertEquals(1, list.size());
        assertEquals("oidc", list.get(0));
    }

    @Test
    void testRedirectLogin_UnknownProvider() {
        when(providers.stream()).thenReturn(List.of(provider1).stream());

        Response response = resource.redirectLogin("unknown", null);
        assertEquals(404, response.getStatus());
    }

    @Test
    void testRedirectLogin_DisabledProvider() {
        when(provider1.isEnabled()).thenReturn(false);
        when(providers.stream()).thenReturn(List.of(provider1).stream());

        Response response = resource.redirectLogin("oidc", null);
        assertEquals(404, response.getStatus());
    }

    @Test
    void testRedirectLogin_Success() {
        when(providers.stream()).thenReturn(List.of(provider1).stream());
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8080/"));
        when(provider1.getAuthorizationUrl(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("http://auth.example.com/oauth");

        Response response = resource.redirectLogin("oidc", "invite-123");
        assertEquals(303, response.getStatus());
        assertEquals("http://auth.example.com/oauth", response.getLocation().toString());
    }

    @Test
    void testCallbackInvalidState() {
        Response response = resource.callback("oidc", "code123", "invalid-state");
        assertEquals(400, response.getStatus());
    }

    @Test
    void testLogout_NoSessionCookie() {
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8080/"));
        Response response = resource.logout();
        assertEquals(200, response.getStatus());
    }

    @Test
    void testLogout_WithSessionCookie() {
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8080/"));
        Cookie sessionCookie =
                new Cookie.Builder("AMSHA_SESSION_ID").value("sess-123").build();
        when(httpHeaders.getCookies()).thenReturn(Map.of("AMSHA_SESSION_ID", sessionCookie));
        when(apiKeyRegistry.computeDigest("sess-123")).thenReturn("digest-123");

        WebSessionDto session = new WebSessionDto(
                "s1",
                "u1",
                "digest-123",
                "csrf-123",
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(86400),
                null,
                null,
                "{}",
                1,
                Instant.now(),
                Instant.now());
        when(webSessionStore.findByTokenDigest("digest-123")).thenReturn(Optional.of(session));

        Response response = resource.logout();
        assertEquals(200, response.getStatus());
        verify(webSessionStore).save(any());
        verify(auditService).recordMandatory(eq("logout"), eq("u1"), anyString(), any(), anyString());
    }

    @Test
    void testS256AndRandomUrlSafe() throws Exception {
        var s256Method = AuthResource.class.getDeclaredMethod("s256", String.class);
        s256Method.setAccessible(true);
        String hash = (String) s256Method.invoke(null, "verifier-string-123");
        assertNotNull(hash);
        assertFalse(hash.isBlank());

        var randomMethod = AuthResource.class.getDeclaredMethod("randomUrlSafe", int.class);
        randomMethod.setAccessible(true);
        String rand = (String) randomMethod.invoke(resource, 32);
        assertNotNull(rand);
        assertFalse(rand.isBlank());
    }

    @Test
    void testEmailConstraintViolated() throws Exception {
        var emailMethod = AuthResource.class.getDeclaredMethod(
                "isEmailConstraintViolated", IdentityInvitationDto.class, ExternalUserProfile.class);
        emailMethod.setAccessible(true);

        IdentityInvitationDto invNoConstraint = new IdentityInvitationDto(
                "inv-1",
                "ws-1",
                "user",
                "digest",
                null,
                Instant.now().plusSeconds(3600),
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
        ExternalUserProfile profile = new ExternalUserProfile("sub-1", "alice@example.com", true, "Alice", null);

        assertFalse((Boolean) emailMethod.invoke(resource, invNoConstraint, profile));

        IdentityInvitationDto invMatching = new IdentityInvitationDto(
                "inv-1",
                "ws-1",
                "user",
                "digest",
                "alice@example.com",
                Instant.now().plusSeconds(3600),
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
        assertFalse((Boolean) emailMethod.invoke(resource, invMatching, profile));

        IdentityInvitationDto invMismatched = new IdentityInvitationDto(
                "inv-1",
                "ws-1",
                "user",
                "digest",
                "bob@example.com",
                Instant.now().plusSeconds(3600),
                null,
                null,
                0,
                Instant.now(),
                Instant.now());
        assertTrue((Boolean) emailMethod.invoke(resource, invMismatched, profile));
    }
}
