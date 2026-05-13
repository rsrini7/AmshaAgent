package com.arcclaw.app.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.security.CryptoConstants;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.quarkus.runtime.Startup;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Startup
@ApplicationScoped
public class ApiKeyRegistry {

    @ConfigProperty(name = "arcclaw.api.keys")
    Optional<Map<String, String>> apiKeys;

    @ConfigProperty(name = "arcclaw.audit.hmac-key")
    String hmacSecret;

    @ConfigProperty(name = "arcclaw.api.rate-limit.requests-per-minute", defaultValue = "20")
    int requestsPerMinute;

    @ConfigProperty(name = "arcclaw.api.rate-limit.mutation-per-minute", defaultValue = "10")
    int mutationPerMinute;

    private final Cache<String, Bucket> rateLimiter = Caffeine.newBuilder()
                                                        .maximumSize(10_000)
                                                        .expireAfterAccess(1, TimeUnit.HOURS)
                                                        .build();

    private final Cache<String, Bucket> mutationRateLimiter = Caffeine.newBuilder()
                                                        .maximumSize(10_000)
                                                        .expireAfterAccess(1, TimeUnit.HOURS)
                                                        .build();

    private Map<String, String> hmacToKeyName;

    @PostConstruct
    void initHmacs() {
        Map<String, String> reverse = new LinkedHashMap<>();
        if (apiKeys.isPresent()) {
            for (Map.Entry<String, String> entry : apiKeys.get().entrySet()) {
                reverse.put(toHex(hmac(entry.getValue())), entry.getKey());
            }
        }
        this.hmacToKeyName  = Collections.unmodifiableMap(reverse);
    }

    public void register(String keyName, String rawKey) {
        Map<String, String> mutable = new LinkedHashMap<>(this.hmacToKeyName);
        mutable.put(toHex(hmac(rawKey)), keyName);
        this.hmacToKeyName = Collections.unmodifiableMap(mutable);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(CryptoConstants.HMAC_SHA256);
            mac.init(new SecretKeySpec(
                hmacSecret.getBytes(StandardCharsets.UTF_8), CryptoConstants.HMAC_SHA256));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC init failed", e);
        }
    }

    public boolean isValidKey(String key) {
        if (key == null) return false;
        return hmacToKeyName.containsKey(toHex(hmac(key)));
    }

    public String getKeyName(String key) {
        if (key == null) return null;
        return hmacToKeyName.get(toHex(hmac(key)));
    }

    public boolean isAllowed(String key) {
        if (key == null) return false;
        Bucket bucket = rateLimiter.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillIntervally(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }

    /**
     * Stricter rate limit for mutation endpoints (POST/PUT/PATCH/DELETE).
     * Uses a separate bucket per key, refilled intervally at {@link #mutationPerMinute} per minute.
     */
    public boolean isMutationAllowed(String key) {
        if (key == null) return false;
        Bucket bucket = mutationRateLimiter.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(mutationPerMinute)
                        .refillIntervally(mutationPerMinute, Duration.ofMinutes(1))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }
}
