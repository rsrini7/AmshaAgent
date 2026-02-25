package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@ApplicationScoped
public class ApiKeyRegistry {

    @ConfigProperty(name = "arcclaw.api.keys")
    Map<String, String> apiKeys;

    @ConfigProperty(name = "arcclaw.api.rate-limit.requests-per-minute", defaultValue = "20")
    int requestsPerMinute;

    @Inject
    Clock clock;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Instant>> rateLimiter = new ConcurrentHashMap<>();

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

        ConcurrentLinkedDeque<Instant> requests = rateLimiter.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        Instant now = clock.instant();
        Instant windowStart = now.minusSeconds(60);

        // Evict expired entries
        while (!requests.isEmpty() && requests.peekFirst().isBefore(windowStart)) {
            requests.pollFirst();
        }

        return requests.size() >= requestsPerMinute;
    }

    public void recordRequest(String key) {
        if (key == null) return;
        ConcurrentLinkedDeque<Instant> requests = rateLimiter.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        requests.addLast(clock.instant());
    }
}
