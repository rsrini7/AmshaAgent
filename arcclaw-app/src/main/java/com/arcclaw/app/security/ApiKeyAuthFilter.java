package com.arcclaw.app.security;

import java.time.Clock;

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

        // /info endpoint is intentionally public — no auth required
        if (path.startsWith("/info") || path.startsWith("/api/v1/info")) {
            return;
        }

        String apiKey = requestContext.getHeaderString(HttpConstants.X_ARCCLAW_API_KEY);
        String safeKey = apiKey != null
            ? apiKey.substring(0, Math.min(4, apiKey.length())) + "****"
            : "null";
        String ip = request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
        String method = requestContext.getMethod();

        if (apiKey == null || apiKey.isBlank()) {
            logAudit(safeKey, path, method, "REJECTED_NO_KEY", ip, System.currentTimeMillis() - startTime);
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AuthErrorResponse("UNAUTHORIZED", "Missing or invalid API key", clock.instant().toString()))
                    .build());
            return;
        }

        if (!apiKeyRegistry.isValidKey(apiKey)) {
            logAudit(safeKey, path, method, "REJECTED_INVALID_KEY", ip, System.currentTimeMillis() - startTime);
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AuthErrorResponse("UNAUTHORIZED", "Missing or invalid API key", clock.instant().toString()))
                    .build());
            return;
        }

        if (!apiKeyRegistry.isAllowed(apiKey)) {
            logAudit(safeKey, path, method, "RATE_LIMITED", ip, System.currentTimeMillis() - startTime);
            requestContext.abortWith(Response.status(429)
                    .header("Retry-After", "60")
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new RateLimitErrorResponse("RATE_LIMITED", "Too many requests. Retry after 60 seconds.", 60, clock.instant().toString()))
                    .build());
            return;
        }

        // Push user identifier to MDC for Audit logs to pick up
        String userId = apiKeyRegistry.getKeyName(apiKey);
        if (userId != null) {
            MDC.put(MdcKeys.USER_ID, userId);
        }
        
        logAudit(safeKey, path, method, "ACCEPTED", ip, System.currentTimeMillis() - startTime);
    }

    private void logAudit(String key, String endpoint, String method, String status, String ip, long durationMs) {
        String msg = String.format("[API-AUTH] key=%s endpoint=%s method=%s status=%s ip=%s duration_ms=%d", key, endpoint, method, status, ip, durationMs);
        if ("ACCEPTED".equals(status)) {
            log.info(msg);
        } else {
            log.warn(msg);
        }
    }
}
