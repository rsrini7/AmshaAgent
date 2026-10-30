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

import com.amsha.persistence.api.AccessRoles;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.security.api.CryptoConstants;
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

    @ConfigProperty(name = "amsha.auth.hmac-key")
    String hmacSecret;

    @ConfigProperty(name = "amsha.api.rate-limit.requests-per-minute", defaultValue = "6000")
    int requestsPerMinute;

    @ConfigProperty(name = "amsha.api.rate-limit.mutation-per-minute", defaultValue = "1000")
    int mutationPerMinute;

    @ConfigProperty(name = "amsha.auth.session.rotation-grace-seconds", defaultValue = "30")
    int rotationGraceSeconds;

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

    private Cache<String, String> rotatedSessionCache;

    @PostConstruct
    void initHmacs() {
        Map<String, String> reverse = new LinkedHashMap<>();
        if (apiKeys.isPresent()) {
            for (Map.Entry<String, String> entry : apiKeys.get().entrySet()) {
                reverse.put(toHex(hmac(entry.getValue())), entry.getKey());
            }
        }
        this.hmacToKeyName = Collections.unmodifiableMap(reverse);
        this.rotatedSessionCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, rotationGraceSeconds)))
                .build();
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

    public synchronized void unregisterByDigest(String digestHex) {
        if (digestHex == null) {
            return;
        }
        Map<String, String> mutable = new LinkedHashMap<>(this.hmacToKeyName);
        mutable.remove(digestHex);
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
            if (optCred.isPresent() && isBootstrapCredentialEligible(optCred.get())) {
                ApiCredentialDto cred = optCred.get();
                Instant now = Instant.now();
                return (cred.expiresAt() == null || !cred.expiresAt().isBefore(now)) && cred.revokedAt() == null;
            }
        }
        return false;
    }

    /**
     * Bootstrap-state gate: a bootstrap-issued credential is only accepted once bootstrap has reached
     * CREDENTIAL_ISSUED (or later). An unactivated CLAIMING reservation never yields a usable key,
     * so a generic durable-credential lookup cannot bypass bootstrap state.
     */
    private boolean isBootstrapCredentialEligible(ApiCredentialDto cred) {
        if (!"bootstrap-cred".equals(cred.credentialId())) {
            return true;
        }
        if (!bootstrapStateStore.isResolvable()) {
            return false;
        }
        return bootstrapStateStore
                .get()
                .getState()
                .map(s -> s.isCredentialIssued() || s.isClaimed())
                .orElse(false);
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
                if (AccessRoles.INTERNAL.equals(name)) {
                    if (bootstrapStateStore.isResolvable()) {
                        return bootstrapStateStore
                                .get()
                                .getState()
                                .map(state -> {
                                    String oid = state.ownerId();
                                    return (oid == null || oid.isBlank()) ? AccessRoles.INTERNAL : oid;
                                })
                                .orElse(AccessRoles.INTERNAL);
                    }
                    return AccessRoles.INTERNAL;
                }
                return name;
            }
        }
        if (apiCredentialStore.isResolvable()) {
            Optional<ApiCredentialDto> optCred = apiCredentialStore.get().findByKeyDigest(inputHex);
            if (optCred.isPresent() && isBootstrapCredentialEligible(optCred.get())) {
                ApiCredentialDto cred = optCred.get();
                return cred.userId();
            }
        }
        return null;
    }

    public boolean hasRole(String key, String role) {
        if (key == null) return false;
        String inputHex = toHex(hmac(key));
        if (apiCredentialStore.isResolvable()) {
            Optional<ApiCredentialDto> optCred = apiCredentialStore.get().findByKeyDigest(inputHex);
            if (optCred.isPresent()) {
                return hasCredentialRole(optCred.get(), role);
            }
        }
        for (Map.Entry<String, String> entry : hmacToKeyName.entrySet()) {
            if (constantTimeEquals(entry.getKey(), inputHex)) {
                String name = entry.getValue();
                if (AccessRoles.INTERNAL.equals(name)) {
                    return AccessRoles.OWNER.equalsIgnoreCase(role) || AccessRoles.INTERNAL.equalsIgnoreCase(role);
                }
                return "SERVICE".equalsIgnoreCase(role) || name.equalsIgnoreCase(role);
            }
        }
        return false;
    }

    private boolean hasCredentialRole(ApiCredentialDto credential, String role) {
        Instant now = Instant.now();
        if (!isBootstrapCredentialEligible(credential)
                || (credential.expiresAt() != null && credential.expiresAt().isBefore(now))
                || credential.revokedAt() != null) {
            return false;
        }
        String roleSet = credential.roleSet();
        if (roleSet == null) {
            return false;
        }
        for (String value : roleSet.split(",")) {
            String declaredRole = value.trim();
            if (declaredRole.equalsIgnoreCase(role)
                    || (AccessRoles.OWNER.equalsIgnoreCase(declaredRole)
                            && AccessRoles.INTERNAL.equalsIgnoreCase(role))) {
                return true;
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

    /**
     * Records a rotated-away session token digest so the previous token keeps resolving
     * to its session for a short grace window (prevents mid-flight request failures).
     * @param oldDigestHex the HMAC hex of the previous session token
     * @param sessionId the session the token maps to
     */
    public void registerRotatedSession(String oldDigestHex, String sessionId) {
        if (oldDigestHex == null || sessionId == null) return;
        rotatedSessionCache.put(oldDigestHex, sessionId);
    }

    /**
     * Resolves a previously-rotated session token digest to its session id, if still within grace.
     */
    public Optional<String> findRotatedSessionId(String digestHex) {
        if (digestHex == null) return Optional.empty();
        return Optional.ofNullable(rotatedSessionCache.getIfPresent(digestHex));
    }
}
