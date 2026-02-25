package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TelegramAllowlistGuard {

    @ConfigProperty(name = "arcclaw.telegram.allowed-users", defaultValue = "")
    Optional<List<String>> allowedUsers;

    @ConfigProperty(name = "arcclaw.telegram.block-response", defaultValue = "⛔ Access denied.")
    String blockResponse;

    @ConfigProperty(name = "arcclaw.telegram.silent-block", defaultValue = "false")
    boolean silentBlock;

    public boolean isAllowed(String username) {
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
