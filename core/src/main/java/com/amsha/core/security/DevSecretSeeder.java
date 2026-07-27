package com.amsha.core.security;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import com.amsha.common.util.LogUtil;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Seeds host OS environment variables into the SecretVault when a workspace
 * session becomes active.
 * <p>
 * Controlled by {@code amsha.auto-seed-secrets} (default: {@code false}).
 * Set {@code AMSHA_AUTO_SEED_SECRETS=true} in your {@code .env} to enable.
 * <p>
 * Seeding is idempotent: if the vault already contains the key for the
 * current session, the host environment is not read again.
 * <p>
 * <strong>Dev/test profile:</strong> {@code seedAuditHmacKey()} generates a
 * random ephemeral key - acceptable for local sessions, never persisted across
 * reboots.
 * <p>
 * <strong>Prod/cli profile with {@code autoSeedEnabled=true}:</strong>
 * {@code seedAuditHmacKeyFromEnv()} reads {@code AMSHA_AUDIT_HMAC_KEY} from
 * the config system (i.e. {@code .env}) and stores it as the system vault key.
 * This gives a stable key across restarts - required for audit log integrity.
 * <p>
 * Safe in production: when {@code amsha.auto-seed-secrets=false} (the default),
 * {@code seedDevSecrets()} is a no-op regardless of environment variables present.
 */
@ApplicationScoped
public class DevSecretSeeder {

    @Inject
    Logger log;

    @Inject
    SecretVault secretVault;

    @ConfigProperty(name = "amsha.auto-seed-secrets", defaultValue = "false")
    boolean autoSeedEnabled;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "dev")
    String profile;

    /**
     * Seeds host OS environment variables into the SecretVault at startup.
     * Runs at {@code @Priority(100)} - before {@code StartupSecurityValidator}
     * which uses CDI default priority (2000) and therefore fires after this.
     */
    public void onStart(@Observes @Priority(100) StartupEvent ev) {
        boolean isProdLike = !"dev".equals(profile) && !"test".equals(profile);

        if (!isProdLike) {
            // Dev/test - ephemeral random HMAC key, fresh every boot
            // Matches the wiped DB lifecycle - no cross-boot audit chain needed
            seedAuditHmacKey();
        } else if (autoSeedEnabled) {
            // Prod/cli - stable HMAC key from .env, persists across reboots
            // Required for a verifiable audit chain
            seedAuditHmacKeyFromEnv();
        } else {
            // Prod/cli with autoSeed=false
            // AMSHA_AUDIT_HMAC_KEY will NOT be seeded automatically.
            // Impact:
            //   - Audit log entries will be written but their HMAC signatures
            //     cannot be verified (no key in vault to verify against).
            //   - StartupSecurityValidator may flag missing audit key depending
            //     on your validation config.
            //   - You must provision AMSHA_AUDIT_HMAC_KEY manually via:
            //       POST /api/v1/vault/system-keys
            //     before audit integrity checks will pass.
            log.warnf(
                    "[DevSecretSeeder] Profile='%s', autoSeedEnabled=false - "
                            + "AMSHA_AUDIT_HMAC_KEY was NOT seeded into the vault. "
                            + "Audit log HMAC verification will fail until the key is "
                            + "provisioned manually. For production, set AMSHA_AUTO_SEED_SECRETS=true "
                            + "in your .env or insert directly into the vault database.",
                    profile);
        }

        if (autoSeedEnabled) {
            seedDevSecrets();
        } else {
            log.debugf(
                    "[DevSecretSeeder] Auto-seed disabled (amsha.auto-seed-secrets=false). "
                            + "Tool secrets (GITHUB, SERPER, BRAVE) were not seeded. "
                            + "Provision them manually by sending the bot a message: store AMSHA_TOOLS_GITHUB_TOKEN=your_token");
        }
    }

    // -------------------------------------------------------------------------
    // Private - HMAC key seeding (unchanged)
    // -------------------------------------------------------------------------

    /**
     * Dev/test only - generates a random ephemeral HMAC key.
     * Do NOT call in prod: the key changes every boot, breaking the audit chain.
     */
    @SuppressWarnings("java:S2119")
    private void seedAuditHmacKey() {
        String keyName = VaultConstants.AUDIT_HMAC_KEY_NAME;
        try {
            boolean needsSeeding = false;
            if (!secretVault.systemKeyExists(keyName)) {
                needsSeeding = true;
            } else {
                try {
                    needsSeeding = secretVault.retrieveSystemKey(keyName).isEmpty();
                } catch (Exception e) {
                    log.warnf(
                            "DevSecretSeeder: existing %s in vault cannot be decrypted (%s); re-seeding ephemeral key",
                            keyName, LogUtil.rootMessage(e));
                    needsSeeding = true;
                }
            }

            if (needsSeeding) {
                byte[] hmacKeyBytes = new byte[32];
                // Instantiate SecureRandom locally — NOT as a static field.
                // GraalVM native image marks java.security.SecureRandom for run-time
                // initialization. A static field would pin the instance into the image
                // heap at build time, causing:
                //   UnsupportedFeatureException: An object of type 'java.security.SecureRandom'
                //   was found in the image heap.
                new SecureRandom().nextBytes(hmacKeyBytes);
                try {
                    secretVault.storeSystemKey(keyName, hmacKeyBytes);
                    log.infof("DevSecretSeeder: seeded %s (ephemeral, dev/test only)", keyName);
                } catch (Exception e) {
                    log.errorf("DevSecretSeeder: failed to seed %s: %s", keyName, LogUtil.rootMessage(e));
                } finally {
                    Arrays.fill(hmacKeyBytes, (byte) 0);
                }
            }
        } catch (Exception e) {
            // Table may not exist yet during startup (e.g., before Flyway migration completes)
            log.debugf(
                    "DevSecretSeeder: could not check/store %s (table may not exist yet): %s",
                    keyName, LogUtil.rootMessage(e));
        }
    }

    /**
     * Prod/cli - reads {@code AMSHA_AUDIT_HMAC_KEY} from config (.env) and
     * stores it as the system vault key. The value must be a Base64-encoded
     * string (as generated by {@code openssl rand -base64 32}).
     * <p>
     * Idempotent: if the key is already present in the vault this is a no-op.
     * The key is zeroed from the heap immediately after storing.
     */
    private void seedAuditHmacKeyFromEnv() {
        String keyName = VaultConstants.AUDIT_HMAC_KEY_NAME;
        if (secretVault.systemKeyExists(keyName)) {
            log.debugf("DevSecretSeeder: %s already in vault, skipping.", keyName);
            return;
        }
        String envValue = ConfigProvider.getConfig()
                .getOptionalValue("AMSHA_AUDIT_HMAC_KEY", String.class)
                .orElse(null);
        if (envValue == null || envValue.isBlank()) {
            log.warnf("DevSecretSeeder: AMSHA_AUDIT_HMAC_KEY not set in config.");
            return;
        }
        byte[] keyBytes = null;
        try {
            keyBytes = Base64.getDecoder().decode(envValue.trim());
            secretVault.storeSystemKey(keyName, keyBytes);
            log.infof("DevSecretSeeder: seeded %s from AMSHA_AUDIT_HMAC_KEY.", keyName);
        } catch (IllegalArgumentException e) {
            log.errorf("DevSecretSeeder: AMSHA_AUDIT_HMAC_KEY is not valid Base64.");
        } catch (Exception e) {
            log.errorf("DevSecretSeeder: failed to seed %s: %s", keyName, LogUtil.rootMessage(e));
        } finally {
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }

    // -------------------------------------------------------------------------
    // Public - tool secret seeding
    // Called by DevSecretSeederStartup after @Priority(100) core seeder runs
    // -------------------------------------------------------------------------

    /**
     * Seeds tool secrets from .env into the SYSTEM vault.
     * Uses storeSystemKey() - no active WorkspaceSession required.
     * Safe to call at startup before any session is bound.
     */
    public void seedDevSecrets() {
        if (!autoSeedEnabled) {
            return;
        }
        seedFromHostEnvAsSystemKey("AMSHA_TOOLS_GITHUB_TOKEN");
        seedFromHostEnvAsSystemKey("AMSHA_TOOLS_SERPER_API_KEY");
        seedFromHostEnvAsSystemKey("AMSHA_TOOLS_BRAVE_API_KEY");
    }

    private void seedFromHostEnvAsSystemKey(String vaultKeyName) {
        // Idempotency - skip if already in system vault
        if (secretVault.systemKeyExists(vaultKeyName)) {
            log.debugf("Secret seeder: %s already in system vault, skipping.", vaultKeyName);
            return;
        }

        String hostValue = ConfigProvider.getConfig()
                .getOptionalValue(vaultKeyName, String.class)
                .orElse(null);

        if (hostValue == null || hostValue.isBlank() || "none".equalsIgnoreCase(hostValue.trim())) {
            log.warnf(
                    "Secret seeder: %s not found or set to 'none' in config. "
                            + "Provide it manually: send 'store %s=<value>' via chat, "
                            + "or set it in .env and restart with AMSHA_AUTO_SEED_SECRETS=true.",
                    vaultKeyName, vaultKeyName);
            return;
        }

        byte[] secretBytes = hostValue.getBytes(StandardCharsets.UTF_8);
        try {
            // storeSystemKey() uses SYSTEM_SESSION_ID - no WorkspaceScope needed
            secretVault.storeSystemKey(vaultKeyName, secretBytes);
            log.infof("Secret seeder: %s seeded into system vault from config.", vaultKeyName);
        } catch (Exception e) {
            log.warnf("Secret seeder: failed to seed %s into system vault: %s", vaultKeyName, LogUtil.rootMessage(e));
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}
