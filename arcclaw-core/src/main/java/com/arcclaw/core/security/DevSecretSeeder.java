package com.arcclaw.core.security;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dev-only service that seeds host OS environment variables into the
 * SecretVault when a workspace session becomes active.
 * <p>
 * Stripped entirely from the production JAR and Native Image by
 * {@code @IfBuildProfile("dev")} — this code cannot execute in production.
 * <p>
 * Seeding is idempotent: if the vault already contains the key for the
 * current session, the host environment is not read again.
 */
@ApplicationScoped
@IfBuildProfile("dev")
public class DevSecretSeeder {

    private static final Logger log = Logger.getLogger(DevSecretSeeder.class);

    @Inject
    SecretVault secretVault;

    /**
     * Call this AFTER {@code WorkspaceScopeHolder.set(sessionId)} is active.
     * Seeds known developer tool tokens from the host environment into the
     * encrypted vault for the current session.
     */
    public void seedDevSecrets() {
        seedFromHostEnv("GITHUB_TOKEN");
        // Add other dev tool env vars here as needed
    }

    private void seedFromHostEnv(String vaultKeyName) {
        // Idempotency check — avoid re-deriving Argon2id key on every message
        try {
            var optionalKey = secretVault.get(vaultKeyName);
            if (optionalKey.isPresent()) {
                // CRITICAL FIX: Must close the VaultKey to zero the byte array!
                try (VaultKey key = optionalKey.get()) {
                    log.debugf("Dev DX: %s already in vault for this session, skipping seed.", vaultKeyName);
                }
                return;
            }
        } catch (Exception e) {
            log.debugf("Dev DX: vault check for %s failed (%s), proceeding to seed.",
                    vaultKeyName, e.getMessage());
        }

        String hostValue = System.getenv(vaultKeyName);
        if (hostValue == null || hostValue.isBlank()) {
            log.warnf("Dev DX: %s not found in host OS environment. " +
                      "Provide it manually via Telegram /store command.", vaultKeyName);
            return;
        }

        byte[] secretBytes = hostValue.getBytes(StandardCharsets.UTF_8);
        try {
            secretVault.store(vaultKeyName, secretBytes);
            log.infof("Dev DX: Seeded %s into SecretVault from host OS.", vaultKeyName);
        } catch (Exception e) {
            log.warnf("Dev DX: Failed to seed %s into vault: %s", vaultKeyName, e.getMessage());
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }
}