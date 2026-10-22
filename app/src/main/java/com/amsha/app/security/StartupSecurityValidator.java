package com.amsha.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Inject;

import java.security.Security;
import java.util.List;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class StartupSecurityValidator {

    private static final String PROFILE_DEV = "dev";
    private static final String PROFILE_TEST = "test";
    private static final String DEV_PEPPER_PREFIX = "dev-pepper";
    private static final String DNS_TTL_PROPERTY = "networkaddress.cache.ttl";
    private static final String DNS_TTL_DISABLED = "0";
    private static final String DNS_TTL_DEFAULT = "30";
    private static final String VAULT_PEPPER_PROPERTY = "amsha.vault.pepper";
    private static final String QUARKUS_PROFILE_PROPERTY = "quarkus.profile";
    private static final String TELEGRAM_ALLOWED_USER_IDS_PROPERTY = "amsha.channel.telegram.allowed-user-ids";
    private static final String TELEGRAM_ALLOWED_USERS_PROPERTY = "amsha.channel.telegram.allowed-users";

    @ConfigProperty(name = VAULT_PEPPER_PROPERTY)
    String pepper;

    @ConfigProperty(name = QUARKUS_PROFILE_PROPERTY)
    String profile;

    @Inject
    SecretVault vault;

    @ConfigProperty(name = ConfigKeys.TELEGRAM_ENABLED_PROPERTY, defaultValue = "false")
    boolean telegramEnabled;

    @ConfigProperty(name = TELEGRAM_ALLOWED_USER_IDS_PROPERTY)
    Optional<List<Long>> allowedUserIds;

    @ConfigProperty(name = TELEGRAM_ALLOWED_USERS_PROPERTY)
    Optional<List<String>> allowedUsers;

    void onStart(@Observes StartupEvent ev) {
        enforceDnsTtl();
        if (PROFILE_DEV.equals(profile) || PROFILE_TEST.equals(profile)) return;
        validatePepper();
        validateHmacKey();
        if (telegramEnabled) {
            validateAllowlist();
        }
    }

    private void enforceDnsTtl() {
        String existingTtl = Security.getProperty(DNS_TTL_PROPERTY);
        if (existingTtl == null || DNS_TTL_DISABLED.equals(existingTtl) || existingTtl.isBlank()) {
            Security.setProperty(DNS_TTL_PROPERTY, DNS_TTL_DEFAULT);
        }
    }

    private void validatePepper() {
        if (pepper.startsWith(DEV_PEPPER_PREFIX)) {
            throw new IllegalStateException("[SECURITY] " + VAULT_PEPPER_PROPERTY + " is using the dev default. "
                    + "Set AMSHA_VAULT_PEPPER in your .env before running in prod.");
        }
    }

    private void validateHmacKey() {
        if (!vault.systemKeyExists(VaultConstants.AUDIT_HMAC_KEY_NAME)) {
            throw new IllegalStateException("FATAL: " + VaultConstants.AUDIT_HMAC_KEY_NAME
                    + " is not present in SecretVault. For first boot, set " + ConfigKeys.AUTO_SEED_SECRETS_ENV
                    + "=true with a stable " + VaultConstants.AUDIT_HMAC_KEY
                    + " value, or provision this key before deploying to production.");
        }
    }

    private void validateAllowlist() {
        boolean hasUserIds = allowedUserIds.isPresent() && !allowedUserIds.get().isEmpty();
        boolean hasUsernames =
                allowedUsers.isPresent() && allowedUsers.get().stream().anyMatch(s -> s != null && !s.isBlank());

        if (!hasUserIds && !hasUsernames) {
            throw new IllegalStateException("[SECURITY] Both " + TELEGRAM_ALLOWED_USER_IDS_PROPERTY + " and "
                    + TELEGRAM_ALLOWED_USERS_PROPERTY + " are empty in "
                    + profile + " profile. " + "Open access is strictly forbidden. "
                    + "Configure at least one allowlist before deploying.");
        }

        if (!hasUserIds && hasUsernames) {
            throw new DeploymentException("SECURITY: " + TELEGRAM_ALLOWED_USER_IDS_PROPERTY + " (numeric) is "
                    + "not configured, but " + TELEGRAM_ALLOWED_USERS_PROPERTY + " "
                    + "(string username list) is. String username allowlists are a "
                    + "username-hijack vector and are forbidden in profile '"
                    + profile + "'. Migrate to numeric Telegram user IDs before deploying.");
        }
    }
}
