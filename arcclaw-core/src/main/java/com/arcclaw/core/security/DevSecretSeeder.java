package com.arcclaw.core.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;

/**
 * Seeds host OS environment variables into the SecretVault when a workspace
 * session becomes active.
 * <p>
 * Controlled by {@code arcclaw.auto-seed-secrets} (default: {@code false}).
 * Set {@code ARCCLAW_AUTO_SEED_SECRETS=true} in your {@code .env} to enable.
 * <p>
 * Seeding is idempotent: if the vault already contains the key for the
 * current session, the host environment is not read again.
 * <p>
 * Safe in production: when {@code arcclaw.auto-seed-secrets=false} (the default),
 * {@code seedDevSecrets()} is a no-op regardless of environment variables present.
 */
@ApplicationScoped
public class DevSecretSeeder {

    @Inject
    Logger log;

    @Inject
    SecretVault secretVault;

    @ConfigProperty(name = "arcclaw.auto-seed-secrets", defaultValue = "false")
    boolean autoSeedEnabled;

    /**
     * Seeds host OS environment variables into the SecretVault at startup.
     */
    public void onStart(@Observes StartupEvent ev) {
        if (!autoSeedEnabled) {
            log.debugf("Auto-seed disabled (arcclaw.auto-seed-secrets=false). Skipping.");
            return;
        }

        // 1. Seed system keys (profile-independent logic but only if auto-seed enabled)
        seedAuditHmacKey();

        // 2. Seed tool tokens (requires active WorkspaceContext/SessionId if called later, 
        // but here it might fail if session is null. Re-checking original code.)
        // The original code said "Call this AFTER WorkspaceContext.run() is active."
        // So I should keep the public seedDevSecrets() for those, 
        // but the audit HMAC key is a SYSTEM key and doesn't need a session.
    }

    private void seedAuditHmacKey() {
        String keyName = "arcclaw-audit-hmac-key";
        if (!secretVault.systemKeyExists(keyName)) {
            byte[] hmacKeyBytes = new byte[32];
            new SecureRandom().nextBytes(hmacKeyBytes);
            try {
                secretVault.storeSystemKey(keyName, hmacKeyBytes);
                log.info("DevSecretSeeder: seeded arcclaw-audit-hmac-key (system key)");
            } catch (Exception e) {
                log.errorf("DevSecretSeeder: failed to seed %s: %s", keyName, e.getMessage());
            } finally {
                Arrays.fill(hmacKeyBytes, (byte) 0);
            }
        }
    }

    /**
     * Call this AFTER {@code WorkspaceContext.run()} is active.
     */
    public void seedDevSecrets() {
        if (!autoSeedEnabled) {
            return;
        }
        seedFromHostEnv("GITHUB_TOKEN");
    }

    private void seedFromHostEnv(String vaultKeyName) {
        // Idempotency check — avoid re-deriving Argon2id key on every message
        try {
            var optionalKey = secretVault.get(vaultKeyName);
            if (optionalKey.isPresent()) {
                // CRITICAL SECURITY PATTERN: Heap Privacy
                // Close VaultKey immediately to zero-out the decrypted byte array
                // and prevent plaintext exposure during a memory dump.
                try (VaultKey _ = optionalKey.get()) {
                    log.debugf("Secret seeder: %s already in vault for this session, skipping.", vaultKeyName);
                }
                return;
            }
        } catch (Exception e) {
            log.debugf("Secret seeder: vault check for %s failed (%s), proceeding to seed.",
                    vaultKeyName, e.getMessage());
        }

        // Use Quarkus ConfigProvider — reads from .env (via DotenvConfigSourceProvider),
        // system properties, and OS environment variables in priority order.
        String hostValue = ConfigProvider.getConfig()
                .getOptionalValue(vaultKeyName, String.class)
                .orElse(null);

        if (hostValue == null || hostValue.isBlank()) {
            log.warnf("Secret seeder: %s not found in config or .env. " +
                      "Provide it manually via the vault tool.", vaultKeyName);
            return;
        }

        byte[] secretBytes = hostValue.getBytes(StandardCharsets.UTF_8);
        try {
            secretVault.store(vaultKeyName, secretBytes);
            log.infof("Secret seeder: %s seeded into SecretVault from config.", vaultKeyName);
        } catch (Exception e) {
            log.warnf("Secret seeder: failed to seed %s into vault: %s", vaultKeyName, e.getMessage());
        } finally {
            // Zero the raw bytes before garbage collection
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}