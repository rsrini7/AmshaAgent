package com.arcclaw.core.governance;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Tracks the last rate-limit hit timestamp across LLM providers.
 * Used to suppress compaction (which requires its own LLM call) when
 * the provider is already throttled — preventing the double-Groq-call
 * pattern that causes cascading "Done." responses in 1 output token.
 */
@ApplicationScoped
public class RateLimitTracker {

    private static final Logger log = Logger.getLogger(RateLimitTracker.class);

    @ConfigProperty(name = "arcclaw.governance.rate-limit-backoff-seconds",
                    defaultValue = "30")
    long backoffSeconds;

    private final ConcurrentHashMap<String, AtomicReference<Instant>> hitsByProvider =
        new ConcurrentHashMap<>();

    public void recordHit(String provider) {
        hitsByProvider
            .computeIfAbsent(provider, p -> new AtomicReference<>(Instant.EPOCH))
            .set(Instant.now());
        log.debugf("RateLimitTracker: rate-limit hit recorded for provider %s at %s", provider, Instant.now());
    }

    public boolean isBackingOff(String provider) {
        AtomicReference<Instant> ref = hitsByProvider.get(provider);
        if (ref == null) return false;
        return Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds));
    }

    /** true if ANY provider is backing off (for global compaction guard). */
    public boolean isAnyBackingOff() {
        return hitsByProvider.values().stream()
            .anyMatch(ref ->
                Instant.now().isBefore(ref.get().plusSeconds(backoffSeconds)));
    }
}
