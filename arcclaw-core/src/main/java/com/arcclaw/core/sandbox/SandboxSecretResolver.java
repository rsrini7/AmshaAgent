package com.arcclaw.core.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SandboxSecretResolver {

    private static final Logger log = Logger.getLogger(SandboxSecretResolver.class);

    private final SecretVault secretVault;

    protected SandboxSecretResolver() {
        this.secretVault = null;
    }

    @Inject
    public SandboxSecretResolver(SecretVault secretVault) {
        this.secretVault = secretVault;
    }

    /**
     * Resolves vault secrets for container injection as raw byte arrays.
     * <p>
     * Resolution order:
     * 1. Session vault (per-user, workspace-scoped)
     * 2. System vault (seeded at startup from .env via DevSecretSeeder)
     * <p>
     * Fail-closed contract:
     * - null map     → IllegalArgumentException (programming error in caller)
     * - empty map    → empty result (valid - tool requires no secrets)
     * - missing key  → SecurityException (vault must be the sole source of truth)
     * <p>
     * The returned byte arrays are owned by this method's callers and MUST be
     * zeroed after use.
     */
    public Map<String, byte[]> resolveSecrets(Map<String, String> requiredSecrets) {
        if (requiredSecrets == null) {
            throw new IllegalArgumentException(
                    "requiredSecrets must not be null - check ToolRegistry.extractRequiredSecrets()");
        }
        if (requiredSecrets.isEmpty()) {
            return new LinkedHashMap<>();
        }

        var resolved = new LinkedHashMap<String, byte[]>();
        for (var entry : requiredSecrets.entrySet()) {
            String envVar = entry.getKey();
            String vaultKeyName = entry.getValue();

            // 1. Try session vault first (workspace-scoped, per-user)
            VaultKey resolvedKey = null;
            try {
                var sessionKey = secretVault.get(vaultKeyName);
                if (sessionKey.isPresent()) {
                    resolvedKey = sessionKey.get();
                    log.debugf("SandboxSecretResolver: resolved %s from session vault", vaultKeyName);
                }
            } catch (Exception e) {
                // Session vault unavailable (e.g. no active WorkspaceScope) - fall through
                log.debugf("SandboxSecretResolver: session vault unavailable for %s (%s), " +
                           "trying system vault", vaultKeyName, e.getMessage());
            }

            // 2. Fall back to system vault (seeded at startup from .env)
            if (resolvedKey == null) {
                var systemKey = secretVault.retrieveSystemKey(vaultKeyName);
                if (systemKey.isPresent()) {
                    resolvedKey = systemKey.get();
                    log.debugf("SandboxSecretResolver: resolved %s from system vault", vaultKeyName);
                }
            }

            // 3. Fail-closed - no hardcoded substitutions, no System.getenv() fallback
            if (resolvedKey == null) {
                throw new SecurityException("Required secret not in vault: " + vaultKeyName);
            }

            try (VaultKey key = resolvedKey) {
                // SECURE TRANSFER: direct byte array copy - no String materialization.
                // The VaultKey source bytes are zeroed on close() above.
                byte[] src = key.get();
                byte[] copy = new byte[src.length];
                System.arraycopy(src, 0, copy, 0, src.length);
                resolved.put(envVar, copy);
            }
        }
        return resolved;
    }
}