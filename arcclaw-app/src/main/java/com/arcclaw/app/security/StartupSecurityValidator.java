package com.arcclaw.app.security;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.DeploymentException;

import com.arcclaw.core.security.SecretVault;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class StartupSecurityValidator {

    @ConfigProperty(name = "arcclaw.vault.pepper")
    String pepper;

    @ConfigProperty(name = "quarkus.profile")
    String profile;

    @Inject
    SecretVault vault;

    @ConfigProperty(name = "arcclaw.channel.telegram.allowed-user-ids")
    Optional<List<Long>> allowedUserIds;

    @ConfigProperty(name = "arcclaw.channel.telegram.allowed-users")
    Optional<List<String>> allowedUsers;

    void onStart(@Observes StartupEvent ev) {
        if (!"dev".equals(profile) && !"test".equals(profile)) {
            // 1. pepper check -> throws IllegalStateException
            if (pepper.startsWith("dev-pepper")) {
                throw new IllegalStateException(
                    "[SECURITY] arcclaw.vault.pepper is using the dev default. " +
                    "Set ARCCLAW_VAULT_PEPPER in your .env before running in prod.");
            }
            // 2. HMAC key check in Vault
            if (!vault.systemKeyExists("arcclaw-audit-hmac-key")) {
                throw new IllegalStateException(
                    "FATAL: arcclaw-audit-hmac-key is not present in SecretVault. " +
                    "Provision this key before deploying to production.");
            }

            boolean hasUserIds = allowedUserIds.isPresent()
                    && !allowedUserIds.get().isEmpty();
            boolean hasUsernames = allowedUsers.isPresent()
                    && allowedUsers.get().stream().anyMatch(s -> s != null && !s.isBlank());

            // 3. allowlist presence -> throws IllegalStateException if both lists empty
            if (!hasUserIds && !hasUsernames) {
                throw new IllegalStateException(
                    "[SECURITY] Both arcclaw.channel.telegram.allowed-user-ids and " +
                    "arcclaw.channel.telegram.allowed-users are empty in " + profile + " profile. " +
                    "Open access is strictly forbidden. " +
                    "Configure at least one allowlist before deploying.");
            }

            // Phase 48: String-username-only allowlist is forbidden in non-dev/non-test
            if (!hasUserIds && hasUsernames) {
                throw new DeploymentException(
                    "SECURITY: arcclaw.channel.telegram.allowed-user-ids (numeric) is " +
                    "not configured, but arcclaw.channel.telegram.allowed-users " +
                    "(string username list) is. String username allowlists are a " +
                    "username-hijack vector and are forbidden in profile '" + profile +
                    "'. Migrate to numeric Telegram user IDs before deploying.");
            }
        }
    }
}

