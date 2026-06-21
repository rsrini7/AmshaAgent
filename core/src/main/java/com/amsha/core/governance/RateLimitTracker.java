package com.amsha.core.governance;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tracks the last rate-limit hit timestamp across LLM providers.
 */
@ApplicationScoped
public class RateLimitTracker {

    private static final Logger log = Logger.getLogger(RateLimitTracker.class);

    @ConfigProperty(name = "amsha.governance.rate-limit-backoff-seconds", defaultValue = "30")
    long backoffSeconds;

    private final ConcurrentHashMap<String, AtomicReference<Instant>> hitsByProvider = new ConcurrentHashMap<>();

    public void recordHit(String provider) {
        hitsByProvider
                .computeIfAbsent(provider, p -> new AtomicReference<>(Instant.EPOCH))
                .set(Instant.now());
        log.debugf("RateLimitTracker: rate-limit hit recorded for provider %s at %s", provider, Instant.now());
    }

    public void recordSuccess(String sessionId) {
        // Implementation if needed
    }

    public void recordFailure(String sessionId) {
        recordHit("unknown");
    }

    public int calculateRetryAfterMs(String message) {
        return 20000; // Mocked
    }

    public boolean isBackingOff(String provider) {
        AtomicReference<Instant> ref = hitsByProvider.get(provider);
        if (ref == null) return false;
        return Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds));
    }

    /** true if ANY provider is backing off (for global compaction guard). */
    public boolean isAnyBackingOff() {
        return hitsByProvider.values().stream()
                .anyMatch(ref -> Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds)));
    }
}
