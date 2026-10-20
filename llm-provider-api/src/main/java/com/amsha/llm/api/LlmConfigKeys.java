package com.amsha.llm.api;

/**
 * Canonical config-key names for the {@code amsha.llm.*} properties.
 * <p>
 * These keys are read from multiple modules (core routing, provider factories,
 * onboarding, the status reflex, and the admin UI). A typo in any copy makes that
 * site silently fall back to its default provider/model, so reference these
 * constants instead of retyping the literals.
 * <p>
 * Every per-provider key follows the {@code amsha.llm.<provider>.<suffix>}
 * convention, where {@code <provider>} is a {@link ModelDefaults} {@code PROVIDER_*}
 * id. The per-provider constants below are the authoritative spelling for the
 * annotation sites ({@code @ConfigProperty} requires a compile-time constant);
 * {@link #providerKey(String, String)} composes the identical key for the runtime
 * resolution paths (the infinite-loop providers). {@code LlmConfigKeysTest} pins the
 * two mechanisms together so a rename cannot desync them.
 */
public final class LlmConfigKeys {

    /** Namespace prefix shared by every key in this holder. */
    public static final String PREFIX = "amsha.llm.";

    /** Suffix for a provider's API-key property. */
    public static final String SUFFIX_API_KEY = "api-key";

    /** Suffix for a provider's API base-URL property. */
    public static final String SUFFIX_BASE_URL = "base-url";

    /** Suffix for a provider's model-id property. */
    public static final String SUFFIX_MODEL = "model";

    /** Suffix for a provider's account-id property. */
    public static final String SUFFIX_ACCOUNT_ID = "account-id";

    /** Selected LLM provider id; values are {@link ModelDefaults#PROVIDER_*}. */
    public static final String PROVIDER = "amsha.llm.provider";

    /** Explicit model override for the selected provider. */
    public static final String MODEL = "amsha.llm.model";

    /** Sampling temperature applied to chat-model construction. */
    public static final String TEMPERATURE = "amsha.llm.temperature";

    /** Whether the SecretVault takes priority over environment credentials. */
    public static final String VAULT_PRIORITY = "amsha.llm.vault-priority";

    /** Whether profile-based model routing is enabled. */
    public static final String PROFILE_ROUTING_ENABLED = "amsha.llm.profile-routing.enabled";

    /** Whether HTTP request/response bodies are logged for LLM calls. */
    public static final String LOG_HTTP = "amsha.llm.log-http";

    // -- Per-provider keys (amsha.llm.<provider>.<suffix>) ----------------
    // Keep these aligned with providerKey(<provider>, <suffix>); LlmConfigKeysTest enforces it.

    /** Antigravity (agy) API key. */
    public static final String AGY_API_KEY = "amsha.llm.agy.api-key";

    /** Antigravity (agy) API base URL. */
    public static final String AGY_BASE_URL = "amsha.llm.agy.base-url";

    /** Antigravity (agy) model id. */
    public static final String AGY_MODEL = "amsha.llm.agy.model";

    /** Claude Code API key. */
    public static final String CLAUDE_CODE_API_KEY = "amsha.llm.claude-code.api-key";

    /** Claude Code API base URL. */
    public static final String CLAUDE_CODE_BASE_URL = "amsha.llm.claude-code.base-url";

    /** Claude Code model id. */
    public static final String CLAUDE_CODE_MODEL = "amsha.llm.claude-code.model";

    /** Cloudflare Workers AI account id. */
    public static final String CLOUDFLARE_ACCOUNT_ID = "amsha.llm.cloudflare.account-id";

    /** Cloudflare Workers AI API key. */
    public static final String CLOUDFLARE_API_KEY = "amsha.llm.cloudflare.api-key";

    /** Cloudflare Workers AI API base URL. */
    public static final String CLOUDFLARE_BASE_URL = "amsha.llm.cloudflare.base-url";

    /** Cloudflare Workers AI model id. */
    public static final String CLOUDFLARE_MODEL = "amsha.llm.cloudflare.model";

    /** Codex API key. */
    public static final String CODEX_API_KEY = "amsha.llm.codex.api-key";

    /** Codex API base URL. */
    public static final String CODEX_BASE_URL = "amsha.llm.codex.base-url";

    /** Codex model id. */
    public static final String CODEX_MODEL = "amsha.llm.codex.model";

    /** Cohere API key. */
    public static final String COHERE_API_KEY = "amsha.llm.cohere.api-key";

    /** Cohere API base URL. */
    public static final String COHERE_BASE_URL = "amsha.llm.cohere.base-url";

    /** Cohere model id. */
    public static final String COHERE_MODEL = "amsha.llm.cohere.model";

    /** Foundry API base URL. */
    public static final String FOUNDRY_BASE_URL = "amsha.llm.foundry.base-url";

    /** Foundry model id. */
    public static final String FOUNDRY_MODEL = "amsha.llm.foundry.model";

    /** Whether Gemini logs outgoing requests. */
    public static final String GEMINI_LOG_REQUESTS = "amsha.llm.gemini.log-requests";

    /** Whether Gemini logs incoming responses. */
    public static final String GEMINI_LOG_RESPONSES = "amsha.llm.gemini.log-responses";

    /** HuggingFace API key. */
    public static final String HUGGINGFACE_API_KEY = "amsha.llm.huggingface.api-key";

    /** HuggingFace API base URL. */
    public static final String HUGGINGFACE_BASE_URL = "amsha.llm.huggingface.base-url";

    /** HuggingFace model id. */
    public static final String HUGGINGFACE_MODEL = "amsha.llm.huggingface.model";

    /** Kimchi API key. */
    public static final String KIMCHI_API_KEY = "amsha.llm.kimchi.api-key";

    /** Kimchi API base URL. */
    public static final String KIMCHI_BASE_URL = "amsha.llm.kimchi.base-url";

    /** Kimchi model id. */
    public static final String KIMCHI_MODEL = "amsha.llm.kimchi.model";

    /** Mistral API key. */
    public static final String MISTRAL_API_KEY = "amsha.llm.mistral.api-key";

    /** Mistral API base URL. */
    public static final String MISTRAL_BASE_URL = "amsha.llm.mistral.base-url";

    /** Mistral model id. */
    public static final String MISTRAL_MODEL = "amsha.llm.mistral.model";

    /** OpenCode API key. */
    public static final String OPENCODE_API_KEY = "amsha.llm.opencode.api-key";

    /** OpenCode API base URL. */
    public static final String OPENCODE_BASE_URL = "amsha.llm.opencode.base-url";

    /** OpenCode model id. */
    public static final String OPENCODE_MODEL = "amsha.llm.opencode.model";

    /** OpenRouter attribution site URL. */
    public static final String OPENROUTER_SITE_URL = "amsha.llm.openrouter.site-url";

    /** OpenRouter attribution app name. */
    public static final String OPENROUTER_APP_NAME = "amsha.llm.openrouter.app-name";

    /** Sarvam API key. */
    public static final String SARVAM_API_KEY = "amsha.llm.sarvam.api-key";

    /** Sarvam API base URL. */
    public static final String SARVAM_BASE_URL = "amsha.llm.sarvam.base-url";

    /** Sarvam model id. */
    public static final String SARVAM_MODEL = "amsha.llm.sarvam.model";

    /**
     * Composes the canonical {@code amsha.llm.<provider>.<suffix>} key.
     *
     * @param provider a {@link ModelDefaults} {@code PROVIDER_*} id
     * @param suffix   one of the {@code SUFFIX_*} values
     * @return the fully-qualified property name
     */
    public static String providerKey(String provider, String suffix) {
        return PREFIX + provider + "." + suffix;
    }

    private LlmConfigKeys() {
        // Prevent instantiation
    }
}
