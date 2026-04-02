package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Thin validation facade over {@link ApiKeyRegistry}.
 *
 * Use this wherever a simple boolean "is this key valid and not rate-limited?"
 * check is needed outside the JAX-RS filter pipeline — e.g. WebSocket
 * handshake handlers, SSE endpoint guards, or service-layer pre-checks.
 *
 * The full audit logging and MDC population remain in {@link ApiKeyAuthFilter};
 * this class intentionally does nothing beyond validity + rate-limit checks.
 */
@ApplicationScoped
public class ApiKeyValidator {

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    /**
     * Returns {@code true} if the key is non-blank, registered, and
     * not currently rate-limited.
     *
     * @param apiKey raw key value from header or query param
     * @return {@code true} when the key may proceed
     */
    public boolean isValid(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return false;
        return apiKeyRegistry.isValidKey(apiKey) && apiKeyRegistry.isAllowed(apiKey);
    }

    /**
     * Returns the human-readable name (userId / key label) registered
     * for this key, or {@code null} if the key is unknown.
     *
     * @param apiKey raw key value
     * @return the key's registered name, or {@code null}
     */
    public String resolveUserId(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        return apiKeyRegistry.getKeyName(apiKey);
    }
}