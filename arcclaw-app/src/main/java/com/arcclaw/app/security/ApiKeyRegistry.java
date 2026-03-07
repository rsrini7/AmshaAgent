package com.arcclaw.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import com.arcclaw.core.security.CryptoConstants;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ApiKeyRegistry {

    @ConfigProperty(name = "arcclaw.api.keys")
    Map<String, String> apiKeys;

    @ConfigProperty(name = "arcclaw.audit.hmac-key")
    String hmacSecret;

    @ConfigProperty(name = "arcclaw.api.rate-limit.requests-per-minute", defaultValue = "20")
    int requestsPerMinute;

    private final Cache<String, Bucket> rateLimiter = Caffeine.newBuilder()
                                                        .maximumSize(10_000)
                                                        .expireAfterAccess(1, TimeUnit.HOURS)
                                                        .build();

    private Map<String, byte[]> storedKeyHmacs;

    @PostConstruct
    void initHmacs() {
        Map<String, byte[]> computed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : apiKeys.entrySet()) {
            computed.put(entry.getKey(), hmac(entry.getValue()));
        }
        this.storedKeyHmacs = Collections.unmodifiableMap(computed);
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
        byte[] incomingHmac = hmac(key);
        boolean found = false;
        for (byte[] storedHmac : storedKeyHmacs.values()) {
            if (MessageDigest.isEqual(storedHmac, incomingHmac)) {
                found = true;
            }
        }
        return found;
    }

    public String getKeyName(String key) {
        if (key == null) return null;
        byte[] incomingHmac = hmac(key);
        String result = null;
        for (Map.Entry<String, byte[]> entry : storedKeyHmacs.entrySet()) {
            if (MessageDigest.isEqual(entry.getValue(), incomingHmac)) {
                result = entry.getKey();
            }
        }
        return result;
    }

    public boolean isAllowed(String key) {
        if (key == null) return false;
        Bucket bucket = rateLimiter.get(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build());
        return bucket.tryConsume(1);
    }
}
