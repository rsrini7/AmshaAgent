package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@ApplicationScoped
public class ApiKeyRegistry {

    @ConfigProperty(name = "arcclaw.api.keys")
    Map<String, String> apiKeys;

    @ConfigProperty(name = "arcclaw.api.rate-limit.requests-per-minute", defaultValue = "20")
    int requestsPerMinute;

    @Inject
    Clock clock;

    private final Cache<String, ConcurrentLinkedDeque<Instant>> rateLimiter = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(2)) // Auto-evicts idle keys after 2 minutes
        .maximumSize(10_000)                      // Hard cap to prevent heap exhaustion
        .build();

    public boolean isValidKey(String key) {
        if (key == null) return false;
        return apiKeys.containsValue(key);
    }

    public String getKeyName(String key) {
        if (key == null) return null;
        for (Map.Entry<String, String> entry : apiKeys.entrySet()) {
            if (entry.getValue().equals(key)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean isRateLimited(String key) {
        
        if (key == null) return false;

        Instant now = clock.instant();
        Instant windowStart = now.minusSeconds(60);

        // Only fetch the existing queue (or return an empty one), do NOT add a timestamp here
        ConcurrentLinkedDeque<Instant> requests = rateLimiter.get(key, k -> new ConcurrentLinkedDeque<>());

        // Evict expired entries
        while (!requests.isEmpty() && requests.peekFirst().isBefore(windowStart)) {
            requests.pollFirst();
        }

        return requests.size() >= requestsPerMinute;
    }

    public void recordRequest(String key) {
        if (key == null) return;
        Instant now = clock.instant();
        // 1. Fetch or create the queue
        ConcurrentLinkedDeque<Instant> requests = rateLimiter.get(key, k -> new ConcurrentLinkedDeque<>());
        // 2. Add the timestamp on a separate line to avoid the void return type mismatch
        requests.addLast(now);
    }
}
