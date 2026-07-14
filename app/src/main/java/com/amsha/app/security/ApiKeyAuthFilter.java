package com.amsha.app.security;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.util.HttpConstants;
import com.amsha.core.util.MdcKeys;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import io.vertx.core.http.HttpServerRequest;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    AuditService auditService;

    @Inject
    Logger log;

    @Inject
    Clock clock;

    @Context
    UriInfo uriInfo;

    @Context
    HttpServerRequest request;

    @Inject
    Instance<WebSessionStorePort> webSessionStore;

    @Inject
    Instance<IdentityStorePort> identityStore;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        long startTime = System.currentTimeMillis();
        String finalPath = getNormalizedPath(uriInfo.getPath());

        if (finalPath.startsWith("/info") || finalPath.startsWith("/onboarding")) {
            return;
        }
        if (finalPath.startsWith("/auth/providers")
                || finalPath.startsWith("/auth/login")
                || finalPath.startsWith("/auth/callback")) {
            String ip =
                    request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
            if (!apiKeyRegistry.isAllowed("auth-ip:" + ip)) {
                requestContext.abortWith(Response.status(429)
                        .header("Retry-After", "60")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(new RateLimitErrorResponse(
                                "RATE_LIMITED",
                                "Too many requests. Retry after 60 seconds.",
                                60,
                                clock.instant().toString()))
                        .build());
                return;
            }
            return;
        }

        String ip = request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
        String method = requestContext.getMethod();

        // 1. Try Browser Web Session Authentication
        Cookie sessionCookie = requestContext.getCookies().get("AMSHA_SESSION_ID");
        String sessionToken = null;
        if (sessionCookie != null && !sessionCookie.getValue().isBlank()) {
            sessionToken = sessionCookie.getValue();
        } else {
            String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith("Bearer sess-")) {
                sessionToken = authHeader.substring(7).trim();
            }
        }

        if (sessionToken != null && webSessionStore.isResolvable()) {
            String digest = apiKeyRegistry.computeDigest(sessionToken);
            Optional<WebSessionDto> optSession = webSessionStore.get().findByTokenDigest(digest);
            if (optSession.isPresent()) {
                WebSessionDto session = optSession.get();
                Instant now = clock.instant();

                if (session.revokedAt() == null
                        && !session.idleExpiresAt().isBefore(now)
                        && !session.absoluteExpiresAt().isBefore(now)) {

                    // Validate CSRF for mutations
                    if (Set.of("POST", "PUT", "DELETE", "PATCH").contains(method)) {
                        String csrfHeader = requestContext.getHeaderString("X-CSRF-Token");
                        if (csrfHeader == null || csrfHeader.isBlank()) {
                            logAudit(
                                    "session",
                                    finalPath,
                                    method,
                                    "REJECTED_CSRF_MISSING",
                                    ip,
                                    System.currentTimeMillis() - startTime);
                            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                                    .type(MediaType.APPLICATION_JSON)
                                    .entity(new AuthErrorResponse("FORBIDDEN", "CSRF token is missing", now.toString()))
                                    .build());
                            return;
                        }
                        String csrfDigest = apiKeyRegistry.computeDigest(csrfHeader);
                        if (!csrfDigest.equals(session.csrfDigest())) {
                            logAudit(
                                    "session",
                                    finalPath,
                                    method,
                                    "REJECTED_CSRF_INVALID",
                                    ip,
                                    System.currentTimeMillis() - startTime);
                            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                                    .type(MediaType.APPLICATION_JSON)
                                    .entity(new AuthErrorResponse("FORBIDDEN", "CSRF token is invalid", now.toString()))
                                    .build());
                            return;
                        }
                    }

                    // Refresh idle expiration (e.g. sliding window of 30 minutes)
                    Instant newIdleExpiry = now.plus(Duration.ofMinutes(30));
                    WebSessionDto updatedSession = new WebSessionDto(
                            session.sessionId(),
                            session.userId(),
                            session.tokenDigest(),
                            session.csrfDigest(),
                            newIdleExpiry,
                            session.absoluteExpiresAt(),
                            session.rotatedAt(),
                            session.revokedAt(),
                            session.safeClientMetadata(),
                            session.revision(),
                            session.createdAt(),
                            now);
                    webSessionStore.get().save(updatedSession);

                    // Set security context and populate MDC
                    MDC.put(MdcKeys.REQUEST_ID, UUID.randomUUID().toString());
                    MDC.put(MdcKeys.USER_ID, session.userId());

                    final SecurityContext originalContext = requestContext.getSecurityContext();
                    requestContext.setSecurityContext(new SecurityContext() {
                        @Override
                        public Principal getUserPrincipal() {
                            return session::userId;
                        }

                        @Override
                        public boolean isUserInRole(String role) {
                            return hasUserRole(session.userId(), role);
                        }

                        @Override
                        public boolean isSecure() {
                            return originalContext.isSecure();
                        }

                        @Override
                        public String getAuthenticationScheme() {
                            return "SESSION";
                        }
                    });

                    logAudit("session", finalPath, method, "ACCEPTED", ip, System.currentTimeMillis() - startTime);
                    return;
                }
            }
        }

        // 2. Fall back to existing API Key authentication
        String apiKey = resolveApiKey(requestContext);

        String safeKey = apiKey != null ? apiKey.substring(0, Math.min(4, apiKey.length())) + "****" : "null";

        if (apiKey == null || apiKey.isBlank()) {
            abortUnauthorized(requestContext, safeKey, finalPath, method, ip, "NO_KEY", startTime);
            return;
        }

        if (!apiKeyRegistry.isValidKey(apiKey)) {
            abortUnauthorized(requestContext, safeKey, finalPath, method, ip, "INVALID_KEY", startTime);
            return;
        }

        String userId = apiKeyRegistry.getKeyName(apiKey);
        boolean isInternalUiKey = apiKeyRegistry.hasRole(apiKey, "OWNER") || apiKeyRegistry.hasRole(apiKey, "internal");
        boolean isReadOnlyPolling =
                "GET".equals(method) && POLL_LIKE_PATHS.stream().anyMatch(finalPath::startsWith);

        if (!(isReadOnlyPolling && isInternalUiKey)) {
            boolean allowed =
                    "GET".equals(method) ? apiKeyRegistry.isAllowed(apiKey) : apiKeyRegistry.isMutationAllowed(apiKey);
            if (!allowed) {
                logAudit(safeKey, finalPath, method, "RATE_LIMITED", ip, System.currentTimeMillis() - startTime);
                auditService.record(
                        "auth-filter",
                        userId,
                        "system",
                        SecurityEventType.AUTH_REJECTED,
                        "reason=RATE_LIMITED key=" + safeKey + " endpoint=" + finalPath + " method=" + method + " ip="
                                + ip);
                requestContext.abortWith(Response.status(429)
                        .header("Retry-After", "60")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(new RateLimitErrorResponse(
                                "RATE_LIMITED",
                                "Too many requests. Retry after 60 seconds.",
                                60,
                                clock.instant().toString()))
                        .build());
                return;
            }
        }

        if (userId != null) {
            MDC.put(MdcKeys.REQUEST_ID, UUID.randomUUID().toString());
            MDC.put(MdcKeys.USER_ID, userId);

            // §85-C: Propagate identity to JAX-RS SecurityContext.
            // Downstream resources (like HitlResource) rely on getUserPrincipal() to authorize operations.
            final String fUserId = userId;
            final String fApiKey = apiKey;
            requestContext.setSecurityContext(new SecurityContext() {
                @Override
                public Principal getUserPrincipal() {
                    return () -> fUserId;
                }

                @Override
                public boolean isUserInRole(String role) {
                    return apiKeyRegistry.hasRole(fApiKey, role);
                }

                @Override
                public boolean isSecure() {
                    return requestContext.getSecurityContext().isSecure();
                }

                @Override
                public String getAuthenticationScheme() {
                    return "API_KEY";
                }
            });
        }

        logAudit(safeKey, finalPath, method, "ACCEPTED", ip, System.currentTimeMillis() - startTime);
    }

    private boolean hasUserRole(String userId, String role) {
        List<WorkspaceMembershipDto> memberships = identityStore.get().listMembershipsByUserId(userId);
        boolean hasRole = memberships.stream()
                .filter(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                .anyMatch(m -> m.role().equalsIgnoreCase(role));
        if (!hasRole && "internal".equalsIgnoreCase(role)) {
            hasRole = memberships.stream()
                    .filter(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                    .anyMatch(m -> m.role().equalsIgnoreCase("OWNER"));
        }
        return hasRole;
    }

    private String getNormalizedPath(String path) {
        String normalizedPath = (path == null) ? "/" : (path.startsWith("/") ? path : "/" + path);
        if (normalizedPath.startsWith("/api/v1/")) {
            normalizedPath = normalizedPath.substring(7);
        } else if (normalizedPath.equals("/api/v1")) {
            normalizedPath = "/";
        }
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedPath;
    }

    private String resolveApiKey(ContainerRequestContext requestContext) {
        String apiKey = requestContext.getHeaderString(HttpConstants.X_AMSHA_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            Cookie apiCookie = requestContext.getCookies().get(HttpConstants.COOKIE_AMSHA_API_KEY);
            if (apiCookie != null) {
                apiKey = apiCookie.getValue();
            }
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = uriInfo.getQueryParameters().getFirst("apiKey");
        }
        return apiKey;
    }

    private void abortUnauthorized(
            ContainerRequestContext requestContext,
            String safeKey,
            String finalPath,
            String method,
            String ip,
            String reason,
            long startTime) {
        logAudit(safeKey, finalPath, method, "REJECTED_" + reason, ip, System.currentTimeMillis() - startTime);
        auditService.record(
                "auth-filter",
                "anonymous",
                "system",
                SecurityEventType.AUTH_REJECTED,
                "reason=" + reason + " key=" + safeKey + " endpoint=" + finalPath + " method=" + method + " ip=" + ip);
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(new AuthErrorResponse(
                        "UNAUTHORIZED",
                        "Missing or invalid API key",
                        clock.instant().toString()))
                .build());
    }

    private static final Set<String> POLL_LIKE_PATHS = Set.of("/hitl", "/agents", "/skills", "/tools", "/chat/stream");

    private void logAudit(String key, String endpoint, String method, String status, String ip, long durationMs) {
        String msg = String.format(
                "[API-AUTH] key=%s endpoint=%s method=%s status=%s ip=%s duration_ms=%d",
                key, endpoint, method, status, ip, durationMs);

        boolean isPollLike = POLL_LIKE_PATHS.stream().anyMatch(endpoint::startsWith);

        if ("ACCEPTED".equals(status)) {
            if (isPollLike) {
                log.trace(msg);
            } else {
                log.debug(msg);
            }
        } else {
            // Rejections: log at DEBUG for poll-like paths to avoid terminal spam during onboarding/stale tabs.
            // Log at WARN for other paths (potential security issues).
            if (isPollLike) {
                log.debug(msg);
            } else {
                log.warn(msg);
            }
        }
    }
}
