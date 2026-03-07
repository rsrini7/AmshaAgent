package com.arcclaw.core.sandbox;

import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class SandboxSecretResolver {

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
     * Fail-closed contract:
     * - null map     → IllegalArgumentException (programming error in caller)
     * - empty map    → empty result (valid — tool requires no secrets)
     * - missing key  → SecurityException (vault must be the sole source of truth)
     * <p>
     * The returned byte arrays are owned by this method's callers and MUST be
     * zeroed after use.
     */
    public Map<String, byte[]> resolveSecrets(Map<String, String> requiredSecrets) {
        if (requiredSecrets == null) {
            throw new IllegalArgumentException(
                    "requiredSecrets must not be null — check ToolRegistry.extractRequiredSecrets()");
        }
        if (requiredSecrets.isEmpty()) {
            return new LinkedHashMap<>();
        }

        var resolved = new LinkedHashMap<String, byte[]>();
        for (var entry : requiredSecrets.entrySet()) {
            String envVar = entry.getKey();
            String vaultKeyName = entry.getValue();

            var optionalKey = secretVault.get(vaultKeyName);

            if (optionalKey.isPresent()) {
                try (VaultKey key = optionalKey.get()) {
                    // SECURE TRANSFER: direct byte array copy — no String materialization.
                    // The VaultKey source bytes are zeroed on close() above.
                    byte[] src = key.get();
                    byte[] copy = new byte[src.length];
                    System.arraycopy(src, 0, copy, 0, src.length);
                    resolved.put(envVar, copy);
                }
            } else {
                // FAIL-CLOSED: no hardcoded substitutions, no System.getenv() fallback.
                throw new SecurityException("Required secret not in vault: " + vaultKeyName);
            }
        }
        return resolved;
    }
}
