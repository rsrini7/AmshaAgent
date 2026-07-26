package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionResourceTest {

    @Mock
    ApiKeyRegistry apiKeyRegistry;

    @Mock
    WebSessionStorePort webSessionStore;

    @Mock
    AuditService auditService;

    @Mock
    SecurityContext securityContext;

    @Mock
    UriInfo uriInfo;

    @Mock
    HttpHeaders httpHeaders;

    @Mock
    Principal principal;

    private SessionResource resource;

    private static final String USER_ID = "usr-1";

    @BeforeEach
    void setUp() {
        resource = new SessionResource();
        setField(resource, "apiKeyRegistry", apiKeyRegistry);
        setField(resource, "webSessionStore", webSessionStore);
        setField(resource, "auditService", auditService);
        setField(resource, "securityContext", securityContext);
        setField(resource, "uriInfo", uriInfo);
        setField(resource, "httpHeaders", httpHeaders);
        setField(resource, "idleTimeoutMinutes", 720);
    }

    @Test
    void testRotateSessionUnauthorizedNoCookie() {
        when(httpHeaders.getCookies()).thenReturn(Map.of());

        Response resp = resource.rotateSession();
        assertEquals(401, resp.getStatus());
    }

    @Test
    void testRotateSessionSuccess() {
        Cookie cookie = new Cookie("AMSHA_SESSION_ID", "old-token");
        when(httpHeaders.getCookies()).thenReturn(Map.of("AMSHA_SESSION_ID", cookie));
        when(apiKeyRegistry.computeDigest(anyString())).thenAnswer(i -> "digest-" + i.getArgument(0));

        Instant now = Instant.now();
        WebSessionDto session = new WebSessionDto(
                "sess-1",
                USER_ID,
                "digest-old-token",
                "csrf-digest",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now);
        when(webSessionStore.findByTokenDigest("digest-old-token")).thenReturn(Optional.of(session));
        when(webSessionStore.save(any())).thenReturn(true);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8080"));

        Response resp = resource.rotateSession();
        assertEquals(204, resp.getStatus());
        assertFalse(resp.getCookies().isEmpty());
    }

    @Test
    void testRevokeAllSessionsSuccess() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn(USER_ID);

        Instant now = Instant.now();
        WebSessionDto session = new WebSessionDto(
                "sess-1",
                USER_ID,
                "old-digest",
                "csrf-digest",
                now.plusSeconds(3600),
                now.plusSeconds(7200),
                null,
                null,
                null,
                1,
                now,
                now);
        when(webSessionStore.listByUserId(USER_ID)).thenReturn(List.of(session));

        Response resp = resource.revokeAllSessions();
        assertEquals(200, resp.getStatus());
        verify(webSessionStore).save(any());
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
