package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TelegramAllowlistGuard {

    private static final Logger log = Logger.getLogger(TelegramAllowlistGuard.class);

    @ConfigProperty(name = "arcclaw.channel.telegram.allowed-user-ids")
    Optional<List<Long>> allowedUserIds;

    @ConfigProperty(name = "arcclaw.telegram.allowed-users", defaultValue = "")
    Optional<List<String>> allowedUsers;

    @ConfigProperty(name = "arcclaw.telegram.block-response", defaultValue = "⛔ Access denied.")
    String blockResponse;

    @ConfigProperty(name = "arcclaw.telegram.silent-block", defaultValue = "false")
    boolean silentBlock;

    public boolean isAllowed(Long userId, String username) {
        // 1. Check strict numeric IDs first (Phase 46)
        if (allowedUserIds.isPresent() && !allowedUserIds.get().isEmpty()) {
            if (userId != null && allowedUserIds.get().contains(userId)) {
                return true;
            }
            // If numeric IDs are configured, we strictly deny anything that doesn't match
            // (Ignoring the deprecated string list entirely for security)
            return false;
        }

        // 2. Fallback to deprecated string username check
        if (allowedUsers.isEmpty() || allowedUsers.get().isEmpty()) {
            return true; // Open access
        }

        if (username == null || username.isBlank()) {
            return false; // Users without a public username cannot be validated against an active allowlist
        }

        String cleanUsername = username.startsWith("@") ? username.substring(1) : username;

        for (String allowed : allowedUsers.get()) {
            String cleanAllowed = allowed.startsWith("@") ? allowed.substring(1) : allowed;
            if (cleanAllowed.equalsIgnoreCase(cleanUsername)) {
                log.warnf("DEPRECATION WARNING: User %s authenticated via deprecated string username allowlist. Migrate to numeric allowed-user-ids.", username);
                return true;
            }
        }

        return false;
    }

    public String getBlockResponse() {
        return blockResponse;
    }

    public boolean isSilentBlock() {
        return silentBlock;
    }
}
