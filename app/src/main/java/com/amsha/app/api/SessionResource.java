package com.amsha.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.app.security.AuthErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/auth/session")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class SessionResource {

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    AuditService auditService;

    @Context
    SecurityContext securityContext;

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders httpHeaders;

    @ConfigProperty(name = "amsha.auth.session.idle-timeout-minutes", defaultValue = "720")
    int idleTimeoutMinutes;

    private final SecureRandom secureRandom = new SecureRandom();

    @POST
    @Path("/rotate")
    public Response rotateSession() {
        Cookie incomingSessionCookie = httpHeaders.getCookies().get("AMSHA_SESSION_ID");
        String sessionToken = incomingSessionCookie != null ? incomingSessionCookie.getValue() : null;
        if (sessionToken == null || sessionToken.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AuthErrorResponse(
                            "UNAUTHORIZED", "No active session", Instant.now().toString()))
                    .build();
        }
        String oldDigest = apiKeyRegistry.computeDigest(sessionToken);
        Optional<WebSessionDto> sessionOpt = webSessionStore.findByTokenDigest(oldDigest);
        if (sessionOpt.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AuthErrorResponse(
                            "UNAUTHORIZED", "Session not found", Instant.now().toString()))
                    .build();
        }
        WebSessionDto session = sessionOpt.get();
        if (session.revokedAt() != null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new AuthErrorResponse(
                            "UNAUTHORIZED", "Session revoked", Instant.now().toString()))
                    .build();
        }

        byte[] sessionBytes = new byte[32];
        secureRandom.nextBytes(sessionBytes);
        String newToken = "sess-" + Base64.getUrlEncoder().withoutPadding().encodeToString(sessionBytes);
        byte[] csrfBytes = new byte[32];
        secureRandom.nextBytes(csrfBytes);
        String newCsrf = "csrf-" + Base64.getUrlEncoder().withoutPadding().encodeToString(csrfBytes);

        String newSessionDigest = apiKeyRegistry.computeDigest(newToken);
        String newCsrfDigest = apiKeyRegistry.computeDigest(newCsrf);
        Instant now = Instant.now();
        WebSessionDto rotated = new WebSessionDto(
                session.sessionId(),
                session.userId(),
                newSessionDigest,
                newCsrfDigest,
                now.plus(Duration.ofMinutes(Math.max(5, Math.min(idleTimeoutMinutes, 1440)))),
                session.absoluteExpiresAt(),
                now,
                session.revokedAt(),
                session.safeClientMetadata(),
                session.revision(),
                session.createdAt(),
                now);
        webSessionStore.save(rotated);

        apiKeyRegistry.registerRotatedSession(oldDigest, session.sessionId());

        boolean secureCookie = "https".equalsIgnoreCase(uriInfo.getBaseUri().getScheme());

        NewCookie sessionCookie = new NewCookie.Builder("AMSHA_SESSION_ID")
                .value(newToken)
                .path("/")
                .maxAge(3600 * 24 * 7)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        NewCookie csrfCookie = new NewCookie.Builder("X-CSRF-Token")
                .value(newCsrf)
                .path("/")
                .maxAge(3600 * 24 * 7)
                .httpOnly(false)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        auditService.record(
                "session-rotate",
                session.userId(),
                "system",
                SecurityEventType.SECURITY,
                "action=rotate session=" + session.sessionId());

        return Response.noContent().cookie(sessionCookie, csrfCookie).build();
    }
}
