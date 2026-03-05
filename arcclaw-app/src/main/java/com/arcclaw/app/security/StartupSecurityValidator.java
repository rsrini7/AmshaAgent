package com.arcclaw.app.security;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class StartupSecurityValidator {

    @ConfigProperty(name = "arcclaw.vault.pepper")
    String pepper;

    @ConfigProperty(name = "arcclaw.audit.hmac-key")
    String hmacKey;

    @ConfigProperty(name = "quarkus.profile")
    String profile;

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
            // 2. HMAC key check -> throws IllegalStateException
            if (hmacKey.startsWith("dev-audit-hmac-key")) {
                throw new IllegalStateException(
                    "[SECURITY] arcclaw.audit.hmac-key is using the dev default. " +
                    "Set ARCCLAW_AUDIT_HMAC_KEY in your .env before running in prod.");
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

            // 4. string-only allowlist -> throws DeploymentException (P1-3)
            if (!hasUserIds && hasUsernames) {
                throw new jakarta.enterprise.inject.spi.DeploymentException(
                    "[SECURITY] String-only allowlist detected in " + profile + " profile. " +
                    "Use arcclaw.channel.telegram.allowed-user-ids for better security.");
            }
        }
    }
}

