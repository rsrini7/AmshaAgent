package com.arcclaw.app.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ApiKeyRegistry {

    @ConfigProperty(name = "arcclaw.api.keys")
    Map<String, String> apiKeys;

    @ConfigProperty(name = "arcclaw.api.rate-limit.requests-per-minute", defaultValue = "20")
    int requestsPerMinute;

    private final ConcurrentHashMap<String, Bucket> rateLimiter = new ConcurrentHashMap<>();

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

    public boolean isAllowed(String key) {
        if (key == null) return false;
        Bucket bucket = rateLimiter.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }
}
