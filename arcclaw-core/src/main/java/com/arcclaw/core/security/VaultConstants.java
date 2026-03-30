package com.arcclaw.core.security;

/**
 * Shared constants for SecretVault keys and session identifiers.
 * Prevents raw string literal scattering and ensures consistency across modules.
 */
public final class VaultConstants {

    private VaultConstants() {
        // Prevent instantiation
    }

    /**
     * The fixed session ID used for system-level operations (e.g., seeding, startup checks).
     */
    public static final String SYSTEM_SESSION_ID = "SYSTEM";

    /**
     * System-level key for Claude Code OAuth provider.
     */
    public static final String CLAUDE_CODE_OAUTH_TOKEN_KEY = "claude-code-oauth-token";

    /**
     * The name of the system secret used to derive the audit hmac key.
     */
    public static final String AUDIT_HMAC_KEY_NAME = "arcclaw-audit-hmac-key";
}
