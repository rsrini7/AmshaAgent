package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelAllowlist;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class TelegramAllowlistGuard implements ChannelAllowlist {

    private static final Logger log = Logger.getLogger(TelegramAllowlistGuard.class);

    @ConfigProperty(name = "arcclaw.channel.telegram.allowed-user-ids")
    Optional<List<Long>> allowedUserIds;

    @ConfigProperty(name = "arcclaw.channel.telegram.allowed-users", defaultValue = "")
    Optional<List<String>> allowedUsers;

    @ConfigProperty(name = "arcclaw.channel.telegram.block-response", defaultValue = "⛔ Access denied.")
    String blockResponse;

    @ConfigProperty(name = "arcclaw.channel.telegram.silent-block", defaultValue = "false")
    boolean silentBlock;

    @Override
    public boolean isAllowed(String userIdStr, String username) {
        // 1. Check strict numeric IDs first (Phase 46)
        boolean hasNumericIds =
                allowedUserIds.isPresent() && !allowedUserIds.get().isEmpty();

        if (hasNumericIds) {
            if (userIdStr != null) {
                try {
                    long userId = Long.parseLong(userIdStr);
                    if (allowedUserIds.get().contains(userId)) {
                        return true;
                    }
                } catch (NumberFormatException _) {
                    log.debugf("Failed to parse allowed user ID: %s", userIdStr);
                }
            }
            // If numeric IDs are configured, we strictly deny anything that doesn't match
            // (Ignoring the deprecated string list entirely for security)
            log.warnf(
                    "SECURITY: Access denied for userId=%s username=%s — not in numeric allowlist",
                    userIdStr, username);
            return false;
        }

        // 2. Fallback to deprecated string username check
        // Robust check for blank strings to handle Quarkus parsing "" as [""]
        boolean hasStringNames =
                allowedUsers.isPresent() && allowedUsers.get().stream().anyMatch(s -> s != null && !s.isBlank());

        if (!hasStringNames) {
            return true; // Open access (neither numeric nor valid string IDs configured)
        }

        if (username == null || username.isBlank()) {
            return false; // Users without a public username cannot be validated against an active allowlist
        }

        String cleanUsername = username.startsWith("@") ? username.substring(1) : username;

        for (String allowed : allowedUsers.get()) {
            if (allowed == null || allowed.isBlank()) continue; // Safely skip blank entries like [""]

            String cleanAllowed = allowed.startsWith("@") ? allowed.substring(1) : allowed;
            if (cleanAllowed.equalsIgnoreCase(cleanUsername)) {
                log.warnf(
                        "DEPRECATION WARNING: User %s authenticated via deprecated string username allowlist. Migrate to numeric allowed-user-ids.",
                        username);
                return true;
            }
        }

        log.warnf("SECURITY: Access denied for userId=%s username=%s — not in string allowlist", userIdStr, username);
        return false;
    }

    @Override
    public String getBlockResponse() {
        return blockResponse;
    }

    @Override
    public boolean isSilentBlock() {
        return silentBlock;
    }
}
