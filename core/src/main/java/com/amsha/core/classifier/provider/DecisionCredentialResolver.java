package com.amsha.core.classifier.provider;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

/**
 * Resolves a provider's credential <em>reference</em> into credential bytes.
 *
 * <p>{@code amsha.decision.providers.slots.<id>.api-key-ref} is a {@code SecretVault} reference, never a
 * literal key. Nothing resolved it before this class existed, which is why a credentialed provider call
 * was impossible rather than merely unapproved. This is the one place that turns the reference into
 * material.
 *
 * <p><b>The returned array is owned by the caller and must be zeroed.</b> The vault's own {@link VaultKey}
 * is closed here, which wipes its internal buffer; the copy handed back is deliberately detached so that
 * closing the vault key does not blank the credential the caller is about to use. A caller that drops the
 * array without zeroing it defeats the point, so the evaluation path zeroes it in a {@code finally}.
 *
 * <p>A refusal names the provider and says the reference could not be resolved. It never echoes the
 * reference value: the reference is operator configuration, and the selector's contract is that a refusal
 * describes configuration without reproducing it.
 */
public final class DecisionCredentialResolver {

    private final SecretVault vault;

    public DecisionCredentialResolver(SecretVault vault) {
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    /**
     * Resolves a credential reference.
     *
     * @param providerId the declared slot id, named in a refusal
     * @param apiKeyRef the {@code SecretVault} reference; blank means "no credential configured"
     * @return the credential bytes, or empty when no reference is configured
     * @throws DecisionProviderUnavailableException when a reference is configured but cannot be resolved
     */
    public Optional<byte[]> resolve(String providerId, String apiKeyRef) {
        String ref = apiKeyRef == null ? "" : apiKeyRef.trim();
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        Optional<VaultKey> found;
        try {
            found = vault.retrieveSystemKey(ref);
        } catch (RuntimeException ex) {
            throw new DecisionProviderUnavailableException(
                    "Decision provider '" + providerId + "' declares a credential reference that the vault"
                            + " rejected while resolving it",
                    ex);
        }
        if (found == null || found.isEmpty()) {
            throw new DecisionProviderUnavailableException(
                    "Decision provider '" + providerId + "' declares a credential reference that is not"
                            + " present in the vault, so the provider cannot be authenticated");
        }
        try (VaultKey key = found.get()) {
            // Detach the material from the vault's buffer before close() wipes it: the caller needs a
            // live copy, and holding the vault key open would keep the secret resident for longer than
            // the request that needs it.
            return Optional.of(key.get().clone());
        }
    }

    /** Zeroes credential material. Call in a {@code finally} once the engine has consumed it. */
    public static void zero(byte[] credential) {
        if (credential != null) {
            Arrays.fill(credential, (byte) 0);
        }
    }
}
