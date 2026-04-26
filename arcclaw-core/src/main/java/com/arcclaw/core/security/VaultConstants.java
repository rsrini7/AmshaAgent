package com.arcclaw.core.security;

import java.util.Set;

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

    /**
     * System-level key for Serper web search API key.
     */
    public static final String SERPER_API_KEY = "serper-api-key";

    /**
     * System-level key for System Prompt.
     */
    public static final String SYSTEM_PROMPT_KEY = "system.prompt";

    /**
     * Key for LLM API Key.
     */
    public static final String LLM_API_KEY = "llm.api.key";

    /**
     * Key for LLM Provider (openai, groq, etc.).
     */
    public static final String LLM_PROVIDER_KEY = "llm.provider.key";

    /**
     * Key for LLM Model.
     */
    public static final String LLM_MODEL_KEY = "llm.model";

    /**
     * A set of all system-level keys that can be set via the vault tool / reflex.
     */
    public static final Set<String> KNOWN_TOOL_SECRET_NAMES = Set.of(
        "ARCCLAW_TOOLS_GITHUB_TOKEN",
        "ARCCLAW_TOOLS_SERPER_API_KEY",
        "ARCCLAW_TOOLS_BRAVE_API_KEY",
        LLM_API_KEY
    );
}
