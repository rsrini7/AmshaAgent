package com.amsha.core.pipeline;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

/**
 * Fallback implementation of {@link SessionIdResolver} used when no database
 * session registry is available (e.g. filesystem backend or test-only mode).
 * <p>
 * Uses deterministic UUID v3 (MD5) so the same Telegram chat always receives
 * the same session ID across restarts. MD5 is acceptable here because the
 * input is a low-entropy public channel identifier — there is no security
 * requirement, only uniqueness and determinism.
 * <p>
 * In SQL-backed deployments, {@code SessionRegistry} is active and overrides
 * this bean with true random UUID v4 persisted to the {@code session_registry}
 * table.
 */
@ApplicationScoped
@DefaultBean
@SuppressWarnings("java:S4790") // MD5 is acceptable for UUID v3 name hashing (non-cryptographic)
public class FallbackSessionIdResolver implements SessionIdResolver {

    private static final Logger log = Logger.getLogger(FallbackSessionIdResolver.class);

    @Override
    public String resolveOrCreate(String channelType, String channelId, String userId) {
        String key = channelType + ":" + channelId + ":" + userId;
        String sessionId =
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        log.infof(
                "[Fs-Backend] No database session registry. "
                        + "Deriving deterministic UUID v3 session ID for [%s/%s/%s] → %s",
                channelType, channelId, userId, sessionId);
        return sessionId;
    }
}
