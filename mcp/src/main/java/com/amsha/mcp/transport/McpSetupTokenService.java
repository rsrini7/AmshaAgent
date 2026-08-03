package com.amsha.mcp.transport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.core.scope.WorkspaceScopeHolder;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;
import com.amsha.mcp.config.McpToolAllowlist;

/** Short-lived, single-use, user-bound handoff tokens for channel-to-UI MCP setup. */
@ApplicationScoped
public class McpSetupTokenService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String HMAC_VAULT_KEY = "MCP_SETUP_TOKEN_HMAC_KEY";
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();

    @Inject
    SecretVault secretVault;

    @Inject
    McpToolAllowlist allowlist;

    private byte[] hmacSecret;

    @PostConstruct
    void initialize() {
        if (allowlist != null && allowlist.setupTokenSecret().isPresent()) {
            hmacSecret = allowlist.setupTokenSecret().get().getBytes(StandardCharsets.UTF_8);
            return;
        }
        if (secretVault != null) {
            Optional<VaultKey> existing = secretVault.retrieveSystemKey(HMAC_VAULT_KEY);
            if (existing.isPresent()) {
                try (VaultKey key = existing.get()) {
                    hmacSecret = Arrays.copyOf(key.get(), key.get().length);
                }
                return;
            }
        }
        byte[] generated = new byte[32];
        try {
            new SecureRandom().nextBytes(generated);
            hmacSecret = Arrays.copyOf(generated, generated.length);
            if (secretVault != null) secretVault.storeSystemKey(HMAC_VAULT_KEY, generated);
        } finally {
            Arrays.fill(generated, (byte) 0);
        }
    }

    public String issue(String userId, String channelType, String serverName) {
        return issue(userId, channelType, "unknown", serverName);
    }

    public String issue(String userId, String channelType, String channelId, String serverName) {
        sweepExpiredGrants();
        requireText(userId, "userId");
        requireText(channelType, "channelType");
        requireText(channelId, "channelId");
        requireText(serverName, "serverName");
        byte[] raw = new byte[32];
        try {
            new SecureRandom().nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            Grant grant = new Grant(
                    userId, channelType, channelId, serverName, Instant.now().plus(TTL));
            grants.put(digest(token), grant);
            persistGrant(digest(token), grant);
            return token;
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public Optional<SetupGrant> redeem(String token, String userId) {
        return redeem(token, userId, null, null);
    }

    public synchronized Optional<SetupGrant> redeem(String token, String userId, String channelType, String channelId) {
        sweepExpiredGrants();
        if (token == null || token.isBlank() || userId == null || userId.isBlank()) return Optional.empty();
        String tokenDigest = digest(token);
        Grant grant = grants.get(tokenDigest);
        if (grant == null && secretVault != null) grant = loadGrant(tokenDigest, userId);
        if (grant == null
                || !userId.equals(grant.userId())
                || grant.expiresAt().isBefore(Instant.now())
                || (channelType != null && !channelType.equals(grant.channelType()))
                || (channelId != null && !channelId.equals(grant.channelId()))
                || (!grants.remove(tokenDigest, grant) && secretVault == null)) {
            return Optional.empty();
        }
        removeGrant(tokenDigest, grant.userId());
        return Optional.of(new SetupGrant(
                grant.userId(), grant.channelType(), grant.channelId(), grant.serverName(), grant.expiresAt()));
    }

    private void sweepExpiredGrants() {
        Instant now = Instant.now();
        grants.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private void persistGrant(String digest, Grant grant) {
        if (secretVault == null) return;
        byte[] payload = serialize(grant).getBytes(StandardCharsets.UTF_8);
        try {
            runInUserVaultScope(grant.userId(), () -> secretVault.store(vaultKey(digest), payload, grant.userId()));
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private Grant loadGrant(String digest, String userId) {
        AtomicReference<Grant> loaded = new AtomicReference<>();
        runInUserVaultScope(
                userId,
                () -> secretVault.get(vaultKey(digest), userId).ifPresent(value -> {
                    try (VaultKey key = value) {
                        loaded.set(deserialize(new String(key.get(), StandardCharsets.UTF_8)));
                    }
                }));
        return loaded.get();
    }

    private void removeGrant(String digest, String userId) {
        if (secretVault == null) return;
        runInUserVaultScope(userId, () -> secretVault.remove(vaultKey(digest), userId));
    }

    private void runInUserVaultScope(String userId, Runnable action) {
        WorkspaceScopeHolder.run(VaultConstants.USER_VAULT_SCOPE_PREFIX + userId, userId, null, action);
    }

    private static String vaultKey(String digest) {
        return "MCP_SETUP_TOKEN:" + digest;
    }

    private static String serialize(Grant grant) {
        return String.join(
                ".",
                encode(grant.userId()),
                encode(grant.channelType()),
                encode(grant.channelId()),
                encode(grant.serverName()),
                Long.toString(grant.expiresAt().toEpochMilli()));
    }

    private static Grant deserialize(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 5) throw new IllegalArgumentException("Invalid MCP setup grant");
        return new Grant(
                decode(parts[0]),
                decode(parts[1]),
                decode(parts[2]),
                decode(parts[3]),
                Instant.ofEpochMilli(Long.parseLong(parts[4])));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String digest(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MCP setup token digest unavailable", e);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private record Grant(String userId, String channelType, String channelId, String serverName, Instant expiresAt) {}

    public record SetupGrant(
            String userId, String channelType, String channelId, String serverName, Instant expiresAt) {}
}
