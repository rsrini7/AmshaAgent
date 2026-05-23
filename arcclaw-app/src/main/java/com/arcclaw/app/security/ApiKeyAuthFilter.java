package com.arcclaw.app.security;

import java.security.Principal;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
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
    AuditService auditService;

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
        String finalPath = getNormalizedPath(uriInfo.getPath());

        if (finalPath.startsWith("/info") || finalPath.startsWith("/onboarding")) {
            return;
        }

        String apiKey = resolveApiKey(requestContext);

        String safeKey = apiKey != null
            ? apiKey.substring(0, Math.min(4, apiKey.length())) + "****"
            : "null";
        String ip = request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
        String method = requestContext.getMethod();

        if (apiKey == null || apiKey.isBlank()) {
            abortUnauthorized(requestContext, safeKey, finalPath, method, ip, "NO_KEY", startTime);
            return;
        }

        if (!apiKeyRegistry.isValidKey(apiKey)) {
            abortUnauthorized(requestContext, safeKey, finalPath, method, ip, "INVALID_KEY", startTime);
            return;
        }

        String userId = apiKeyRegistry.getKeyName(apiKey);
        boolean isInternalUiKey = "internal".equals(userId);
        boolean isReadOnlyPolling = "GET".equals(method) && POLL_LIKE_PATHS.stream().anyMatch(finalPath::startsWith);

        if (!(isReadOnlyPolling && isInternalUiKey)) {
            boolean allowed = "GET".equals(method)
                ? apiKeyRegistry.isAllowed(apiKey)
                : apiKeyRegistry.isMutationAllowed(apiKey);
            if (!allowed) {
                logAudit(safeKey, finalPath, method, "RATE_LIMITED", ip, System.currentTimeMillis() - startTime);
                auditService.record("auth-filter", userId, "system",
                        SecurityEventType.AUTH_REJECTED,
                        "reason=RATE_LIMITED key=" + safeKey + " endpoint=" + finalPath + " method=" + method + " ip=" + ip);
                requestContext.abortWith(Response.status(429)
                        .header("Retry-After", "60")
                        .type(MediaType.APPLICATION_JSON)
                        .entity(new RateLimitErrorResponse("RATE_LIMITED", "Too many requests. Retry after 60 seconds.", 60, clock.instant().toString()))
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
            requestContext.setSecurityContext(new SecurityContext() {
                @Override public Principal getUserPrincipal() { return () -> fUserId; }
                @Override public boolean isUserInRole(String role) { return "internal".equals(fUserId); }
                @Override public boolean isSecure() { return requestContext.getSecurityContext().isSecure(); }
                @Override public String getAuthenticationScheme() { return "API_KEY"; }
            });
        }

        logAudit(safeKey, finalPath, method, "ACCEPTED", ip, System.currentTimeMillis() - startTime);
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
        String apiKey = requestContext.getHeaderString(HttpConstants.X_ARCCLAW_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            Cookie apiCookie = requestContext.getCookies().get(HttpConstants.COOKIE_ARCCLAW_API_KEY);
            if (apiCookie != null) {
                apiKey = apiCookie.getValue();
            }
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = uriInfo.getQueryParameters().getFirst("apiKey");
        }
        return apiKey;
    }

    private void abortUnauthorized(ContainerRequestContext requestContext, String safeKey, String finalPath,
            String method, String ip, String reason, long startTime) {
        logAudit(safeKey, finalPath, method, "REJECTED_" + reason, ip, System.currentTimeMillis() - startTime);
        auditService.record("auth-filter", "anonymous", "system",
                SecurityEventType.AUTH_REJECTED,
                "reason=" + reason + " key=" + safeKey + " endpoint=" + finalPath + " method=" + method + " ip=" + ip);
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON)
                .entity(new AuthErrorResponse("UNAUTHORIZED", "Missing or invalid API key", clock.instant().toString()))
                .build());
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
