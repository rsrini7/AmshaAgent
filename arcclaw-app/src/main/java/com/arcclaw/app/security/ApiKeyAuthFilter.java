package com.arcclaw.app.security;

import java.time.Clock;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import com.arcclaw.core.util.HttpConstants;
import com.arcclaw.core.util.MdcKeys;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    Logger log;

    @Inject
    Clock clock;

    @Context
    UriInfo uriInfo;

    @Context
    HttpServerRequest request;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        long startTime = System.currentTimeMillis();
        String path = uriInfo.getPath();
        // Normalize path: ensure leading slash and handle optional /api/v1 prefix
        String normalizedPath = (path == null) ? "/" : (path.startsWith("/") ? path : "/" + path);
        if (normalizedPath.startsWith("/api/v1/")) {
            normalizedPath = normalizedPath.substring(7);
        } else if (normalizedPath.equals("/api/v1")) {
            normalizedPath = "/";
        }
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }

        final String finalPath = normalizedPath;

        if (finalPath.startsWith("/info") || finalPath.startsWith("/onboarding")) {
            return;
        }

        // Header first; fall back to query param for SSE/EventSource
        // (browsers cannot set custom headers on EventSource).
        // Accepts both "apiKey" (current frontend) and legacy "key" param.
        String apiKey = requestContext.getHeaderString(HttpConstants.X_ARCCLAW_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = uriInfo.getQueryParameters().getFirst("apiKey");
        }

        String safeKey = apiKey != null
            ? apiKey.substring(0, Math.min(4, apiKey.length())) + "****"
            : "null";
        String ip = request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
        String method = requestContext.getMethod();

        if (apiKey == null || apiKey.isBlank()) {
            logAudit(safeKey, finalPath, method, "REJECTED_NO_KEY", ip, System.currentTimeMillis() - startTime);
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AuthErrorResponse("UNAUTHORIZED", "Missing or invalid API key", clock.instant().toString()))
                    .build());
            return;
        }

        if (!apiKeyRegistry.isValidKey(apiKey)) {
            logAudit(safeKey, finalPath, method, "REJECTED_INVALID_KEY", ip, System.currentTimeMillis() - startTime);
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AuthErrorResponse("UNAUTHORIZED", "Missing or invalid API key", clock.instant().toString()))
                    .build());
            return;
        }

        String userId = apiKeyRegistry.getKeyName(apiKey);
        boolean isInternalUiKey = "internal".equals(userId);
        boolean isReadOnlyPolling = "GET".equals(method) && POLL_LIKE_PATHS.stream().anyMatch(finalPath::startsWith);

        if (!(isReadOnlyPolling && isInternalUiKey)) {
            if (!apiKeyRegistry.isAllowed(apiKey)) {
                logAudit(safeKey, finalPath, method, "RATE_LIMITED", ip, System.currentTimeMillis() - startTime);
                requestContext.abortWith(Response.status(429)
                        .header("Retry-After", "60")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(new RateLimitErrorResponse("RATE_LIMITED", "Too many requests. Retry after 60 seconds.", 60, clock.instant().toString()))
                        .build());
                return;
            }
        }

        if (userId != null) {
            MDC.put(MdcKeys.USER_ID, userId);
            
            // §85-C: Propagate identity to JAX-RS SecurityContext.
            // Downstream resources (like HitlResource) rely on getUserPrincipal() to authorize operations.
            final String fUserId = userId;
            requestContext.setSecurityContext(new jakarta.ws.rs.core.SecurityContext() {
                @Override public java.security.Principal getUserPrincipal() { return () -> fUserId; }
                @Override public boolean isUserInRole(String role) { return "internal".equals(fUserId); }
                @Override public boolean isSecure() { return requestContext.getSecurityContext().isSecure(); }
                @Override public String getAuthenticationScheme() { return "API_KEY"; }
            });
        }

        logAudit(safeKey, finalPath, method, "ACCEPTED", ip, System.currentTimeMillis() - startTime);
    }

    private static final Set<String> POLL_LIKE_PATHS = Set.of(
        "/hitl", "/agents", "/skills", "/tools", "/chat/stream"
    );

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