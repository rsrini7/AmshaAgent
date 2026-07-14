package com.amsha.app.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.amsha.core.security.CryptoConstants;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.quarkus.runtime.Startup;

@Startup
@ApplicationScoped
public class ApiKeyRegistry {

    @ConfigProperty(name = "amsha.api.keys")
    Optional<Map<String, String>> apiKeys;

    @ConfigProperty(name = "amsha.audit.hmac-key")
    String hmacSecret;

    @ConfigProperty(name = "amsha.api.rate-limit.requests-per-minute", defaultValue = "20")
    int requestsPerMinute;

    @ConfigProperty(name = "amsha.api.rate-limit.mutation-per-minute", defaultValue = "10")
    int mutationPerMinute;

    @Inject
    Instance<ApiCredentialStorePort> apiCredentialStore;

    @Inject
    Instance<BootstrapStateStorePort> bootstrapStateStore;

    private final Cache<String, Bucket> rateLimiter = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    private final Cache<String, Bucket> mutationRateLimiter = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    private volatile Map<String, String> hmacToKeyName;

    @PostConstruct
    void initHmacs() {
        Map<String, String> reverse = new LinkedHashMap<>();
        if (apiKeys.isPresent()) {
            for (Map.Entry<String, String> entry : apiKeys.get().entrySet()) {
                reverse.put(toHex(hmac(entry.getValue())), entry.getKey());
            }
        }
        this.hmacToKeyName = Collections.unmodifiableMap(reverse);
    }

    public synchronized void register(String keyName, String rawKey) {
        Map<String, String> mutable = new LinkedHashMap<>(this.hmacToKeyName);
        mutable.put(toHex(hmac(rawKey)), keyName);
        this.hmacToKeyName = Collections.unmodifiableMap(mutable);
    }

    public synchronized void unregister(String rawKey) {
        Map<String, String> mutable = new LinkedHashMap<>(this.hmacToKeyName);
        mutable.remove(toHex(hmac(rawKey)));
        this.hmacToKeyName = Collections.unmodifiableMap(mutable);
    }

    public String computeDigest(String value) {
        if (value == null) return null;
        return toHex(hmac(value));
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
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), CryptoConstants.HMAC_SHA256));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC init failed", e);
        }
    }

    public boolean isValidKey(String key) {
        if (key == null) return false;
        String inputHex = toHex(hmac(key));
        boolean found = false;
        for (String storedHex : hmacToKeyName.keySet()) {
            found |= constantTimeEquals(storedHex, inputHex);
        }
        if (found) {
            return true;
        }
        if (apiCredentialStore.isResolvable()) {
            Optional<ApiCredentialDto> optCred = apiCredentialStore.get().findByKeyDigest(inputHex);
            if (optCred.isPresent()) {
                ApiCredentialDto cred = optCred.get();
                Instant now = Instant.now();
                return (cred.expiresAt() == null || !cred.expiresAt().isBefore(now)) && cred.revokedAt() == null;
            }
        }
        return false;
    }

    /**
     * Resolves the key label or userId.
     * Note: Assumes {@link #isValidKey(String)} has already gated expiry and revocation check.
     */
    public String getKeyName(String key) {
        if (key == null) return null;
        String inputHex = toHex(hmac(key));
        for (Map.Entry<String, String> entry : hmacToKeyName.entrySet()) {
            if (constantTimeEquals(entry.getKey(), inputHex)) {
                String name = entry.getValue();
                if ("internal".equals(name)) {
                    if (bootstrapStateStore.isResolvable()) {
                        return bootstrapStateStore
                                .get()
                                .getState()
                                .map(state -> {
                                    String oid = state.ownerId();
                                    return (oid == null || oid.isBlank()) ? "internal" : oid;
                                })
                                .orElse("internal");
                    }
                    return "internal";
                }
                return name;
            }
        }
        if (apiCredentialStore.isResolvable()) {
            Optional<ApiCredentialDto> optCred = apiCredentialStore.get().findByKeyDigest(inputHex);
            if (optCred.isPresent()) {
                ApiCredentialDto cred = optCred.get();
                return cred.userId();
            }
        }
        return null;
    }

    public boolean hasRole(String key, String role) {
        if (key == null) return false;
        String inputHex = toHex(hmac(key));
        for (Map.Entry<String, String> entry : hmacToKeyName.entrySet()) {
            if (constantTimeEquals(entry.getKey(), inputHex)) {
                String name = entry.getValue();
                if ("internal".equals(name)) {
                    return "OWNER".equalsIgnoreCase(role) || "internal".equalsIgnoreCase(role);
                }
                return "SERVICE".equalsIgnoreCase(role) || name.equalsIgnoreCase(role);
            }
        }
        if (apiCredentialStore.isResolvable()) {
            Optional<ApiCredentialDto> optCred = apiCredentialStore.get().findByKeyDigest(inputHex);
            if (optCred.isPresent()) {
                ApiCredentialDto cred = optCred.get();
                Instant now = Instant.now();
                if ((cred.expiresAt() == null || !cred.expiresAt().isBefore(now)) && cred.revokedAt() == null) {
                    String roleSet = cred.roleSet();
                    if (roleSet != null) {
                        for (String r : roleSet.split(",")) {
                            String trimmed = r.trim();
                            if (trimmed.equalsIgnoreCase(role)
                                    || ("OWNER".equalsIgnoreCase(trimmed) && "internal".equalsIgnoreCase(role))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int xor = 0;
        for (int i = 0; i < a.length(); i++) {
            xor |= a.charAt(i) ^ b.charAt(i);
        }
        return xor == 0;
    }

    public boolean isAllowed(String key) {
        if (key == null) return false;
        Bucket bucket = rateLimiter.get(
                key,
                k -> Bucket.builder()
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
        Bucket bucket = mutationRateLimiter.get(
                key,
                k -> Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(mutationPerMinute)
                                .refillIntervally(mutationPerMinute, Duration.ofMinutes(1))
                                .build())
                        .build());
        return bucket.tryConsume(1);
    }
}
