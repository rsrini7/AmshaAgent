package com.arcclaw.core.governance;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.enterprise.context.ApplicationScoped;
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

    // How long to suppress compaction after a rate-limit hit
    private static final long BACKOFF_SECONDS = 30;

    private final AtomicReference<Instant> lastHit = new AtomicReference<>(Instant.EPOCH);

    public void recordHit() {
        lastHit.set(Instant.now());
        log.debugf("RateLimitTracker: rate-limit hit recorded at %s", Instant.now());
    }

    public boolean isBackingOff() {
        return Instant.now().isBefore(lastHit.get().plusSeconds(BACKOFF_SECONDS));
    }
}