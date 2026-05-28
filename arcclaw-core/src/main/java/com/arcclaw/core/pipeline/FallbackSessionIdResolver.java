package com.arcclaw.core.pipeline;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

/**
 * Fallback implementation of {@link SessionIdResolver} that uses UUID v3 (MD5).
 * <p>
 * Used in {@code arcclaw-core} tests and any deployment that does not include
 * {@code arcclaw-app}'s {@code SessionRegistry}.
 * <p>
 * In production (arcclaw-app), {@code SessionRegistry} is a standard CDI bean
 * which automatically overrides this default bean without any extra configuration.
 * <p>
 * <strong>Do not use in production:</strong> MD5 (UUID v3) is weak and lacks
 * the {@code channelType} component.
 */
@ApplicationScoped
@DefaultBean
public class FallbackSessionIdResolver implements SessionIdResolver {

    private static final Logger log = Logger.getLogger(FallbackSessionIdResolver.class);

    @Override
    public String resolveOrCreate(String channelType, String channelId, String userId) {
        String key = channelId + ":" + userId;
        String sessionId =
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        log.warnf(
                "FallbackSessionIdResolver: using legacy MD5 session ID for [%s/%s/%s] → %s",
                channelType, channelId, userId, sessionId);
        return sessionId;
    }
}
