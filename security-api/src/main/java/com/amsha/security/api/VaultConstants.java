package com.amsha.security.api;

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
     * Stable SecretVault partition for user-owned credentials that must survive a chat-session restart.
     */
    public static final String USER_VAULT_SCOPE_PREFIX = "USER:";

    /** Stable SecretVault partition for credentials shared by an authorized workspace. */
    public static final String WORKSPACE_VAULT_SCOPE_PREFIX = "WORKSPACE:";

    /**
     * System-level key for Claude Code OAuth provider.
     */
    public static final String CLAUDE_CODE_OAUTH_TOKEN_KEY = "claude-code-oauth-token";

    /**
     * System-level key for Codex CLI (ChatGPT Plus) OAuth provider.
     */
    public static final String CODEX_OAUTH_TOKEN_KEY = "codex-oauth-token";

    /**
     * System-level key for Antigravity (agy) CLI OAuth provider.
     */
    public static final String AGY_OAUTH_TOKEN_KEY = "agy-oauth-token";

    /**
     * System-level key for Kimchi CLI OAuth provider.
     */
    public static final String KIMCHI_OAUTH_TOKEN_KEY = "kimchi-oauth-token";

    /**
     * The name of the system secret used to derive the audit hmac key.
     */
    public static final String AUDIT_HMAC_KEY_NAME = "amsha-audit-hmac-key";

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

    /** Optional custom base URL selected by the onboarding wizard. */
    public static final String LLM_BASE_URL_KEY = "llm.base.url";

    // -- Environment-variable names for system secrets --------------------
    // These are the .env / system-property spellings the process reads (and the setup wizard, the
    // env-file validator, and the CLI utilities present). They are distinct from the vault *key*
    // names above: e.g. AUDIT_HMAC_KEY (env) seeds the AUDIT_HMAC_KEY_NAME (vault key) entry.

    /** GitHub personal access token (host/tool secret). */
    public static final String TOOLS_GITHUB_TOKEN = "AMSHA_TOOLS_GITHUB_TOKEN";
    /** Private key for the selected-repository GitHub App source connector. */
    public static final String GITHUB_APP_PRIVATE_KEY = "AMSHA_GITHUB_APP_PRIVATE_KEY";

    /** Serper web-search API key (host/tool secret). */
    public static final String TOOLS_SERPER_API_KEY = "AMSHA_TOOLS_SERPER_API_KEY";

    /** Brave web-search API key (host/tool secret). */
    public static final String TOOLS_BRAVE_API_KEY = "AMSHA_TOOLS_BRAVE_API_KEY";

    /** Vault encryption pepper (Base64). */
    public static final String VAULT_PEPPER = "AMSHA_VAULT_PEPPER";

    /** Audit HMAC key (Base64); seeds the {@link #AUDIT_HMAC_KEY_NAME} vault entry. */
    public static final String AUDIT_HMAC_KEY = "AMSHA_AUDIT_HMAC_KEY";

    /** Auth HMAC key (Base64). */
    public static final String AUTH_HMAC_KEY = "AMSHA_AUTH_HMAC_KEY";

    /** Bootstrap internal API key. */
    public static final String API_KEYS_INTERNAL = "AMSHA_API_KEYS_INTERNAL";

    /** Bootstrap CI API key. */
    public static final String API_KEYS_CI = "AMSHA_API_KEYS_CI";

    /** Bootstrap admin API key. */
    public static final String API_KEYS_ADMIN = "AMSHA_API_KEYS_ADMIN";

    /**
     * A set of all system-level keys that can be set via the vault tool / reflex.
     */
    public static final Set<String> KNOWN_TOOL_SECRET_NAMES =
            Set.of(TOOLS_GITHUB_TOKEN, TOOLS_SERPER_API_KEY, TOOLS_BRAVE_API_KEY, LLM_API_KEY);
}
