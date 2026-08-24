package com.amsha.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;

import java.security.Security;
import java.util.List;
import java.util.Optional;

import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class StartupSecurityValidator {

    @ConfigProperty(name = "amsha.vault.pepper")
    String pepper;

    @ConfigProperty(name = "quarkus.profile")
    String profile;

    @Inject
    SecretVault vault;

    @ConfigProperty(name = "amsha.channel.telegram.allowed-user-ids")
    Optional<List<Long>> allowedUserIds;

    @ConfigProperty(name = "amsha.channel.telegram.allowed-users")
    Optional<List<String>> allowedUsers;

    void onStart(@Observes StartupEvent ev) {
        enforceDnsTtl();
        if ("dev".equals(profile) || "test".equals(profile)) return;
        validatePepper();
        validateHmacKey();
        validateAllowlist();
    }

    private void enforceDnsTtl() {
        String existingTtl = Security.getProperty("networkaddress.cache.ttl");
        if (existingTtl == null || "0".equals(existingTtl) || existingTtl.isBlank()) {
            Security.setProperty("networkaddress.cache.ttl", "30");
        }
    }

    private void validatePepper() {
        if (pepper.startsWith("dev-pepper")) {
            throw new IllegalStateException("[SECURITY] amsha.vault.pepper is using the dev default. "
                    + "Set AMSHA_VAULT_PEPPER in your .env before running in prod.");
        }
    }

    private void validateHmacKey() {
        if (!vault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)) {
            throw new IllegalStateException("FATAL: " + VaultConstants.AUDIT_HMAC_KEY_NAME
                    + " is not present in SecretVault. " + "Provision this key before deploying to production.");
        }
    }

    private void validateAllowlist() {
        boolean hasUserIds = allowedUserIds.isPresent() && !allowedUserIds.get().isEmpty();
        boolean hasUsernames =
                allowedUsers.isPresent() && allowedUsers.get().stream().anyMatch(s -> s != null && !s.isBlank());

        if (!hasUserIds && !hasUsernames) {
            throw new IllegalStateException("[SECURITY] Both amsha.channel.telegram.allowed-user-ids and "
                    + "amsha.channel.telegram.allowed-users are empty in "
                    + profile + " profile. " + "Open access is strictly forbidden. "
                    + "Configure at least one allowlist before deploying.");
        }

        if (!hasUserIds && hasUsernames) {
            throw new DeploymentException("SECURITY: amsha.channel.telegram.allowed-user-ids (numeric) is "
                    + "not configured, but amsha.channel.telegram.allowed-users "
                    + "(string username list) is. String username allowlists are a "
                    + "username-hijack vector and are forbidden in profile '"
                    + profile + "'. Migrate to numeric Telegram user IDs before deploying.");
        }
    }
}
