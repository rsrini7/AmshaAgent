package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code amsha.llm.<provider>.<suffix>} contract.
 *
 * <p>The annotation sites use the compile-time per-provider constants, while the infinite-loop providers
 * compose the same key at runtime via {@link LlmConfigKeys#providerKey(String, String)}. These tests are
 * the guard that a rename of the prefix, a suffix, or a provider id cannot silently desync the two
 * mechanisms — the drift would otherwise make one path read a different key and fall back to a default.
 */
class LlmConfigKeysTest {

    @Test
    void prefixAndSuffixesKeepTheirLiteralValues() {
        assertEquals("amsha.llm.", LlmConfigKeys.PREFIX);
        assertEquals("api-key", LlmConfigKeys.SUFFIX_API_KEY);
        assertEquals("base-url", LlmConfigKeys.SUFFIX_BASE_URL);
        assertEquals("model", LlmConfigKeys.SUFFIX_MODEL);
        assertEquals("account-id", LlmConfigKeys.SUFFIX_ACCOUNT_ID);
    }

    @Test
    void perProviderConstantsMatchTheComposedKey() {
        assertKey(ModelDefaults.PROVIDER_AGY, "api-key", LlmConfigKeys.AGY_API_KEY);
        assertKey(ModelDefaults.PROVIDER_AGY, "base-url", LlmConfigKeys.AGY_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_AGY, "model", LlmConfigKeys.AGY_MODEL);

        assertKey(ModelDefaults.PROVIDER_CLAUDE_CODE, "api-key", LlmConfigKeys.CLAUDE_CODE_API_KEY);
        assertKey(ModelDefaults.PROVIDER_CLAUDE_CODE, "base-url", LlmConfigKeys.CLAUDE_CODE_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_CLAUDE_CODE, "model", LlmConfigKeys.CLAUDE_CODE_MODEL);

        assertKey(ModelDefaults.PROVIDER_CLOUDFLARE, "account-id", LlmConfigKeys.CLOUDFLARE_ACCOUNT_ID);
        assertKey(ModelDefaults.PROVIDER_CLOUDFLARE, "api-key", LlmConfigKeys.CLOUDFLARE_API_KEY);
        assertKey(ModelDefaults.PROVIDER_CLOUDFLARE, "base-url", LlmConfigKeys.CLOUDFLARE_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_CLOUDFLARE, "model", LlmConfigKeys.CLOUDFLARE_MODEL);

        assertKey(ModelDefaults.PROVIDER_CODEX, "api-key", LlmConfigKeys.CODEX_API_KEY);
        assertKey(ModelDefaults.PROVIDER_CODEX, "base-url", LlmConfigKeys.CODEX_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_CODEX, "model", LlmConfigKeys.CODEX_MODEL);

        assertKey(ModelDefaults.PROVIDER_COHERE, "api-key", LlmConfigKeys.COHERE_API_KEY);
        assertKey(ModelDefaults.PROVIDER_COHERE, "base-url", LlmConfigKeys.COHERE_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_COHERE, "model", LlmConfigKeys.COHERE_MODEL);

        assertKey(ModelDefaults.PROVIDER_FOUNDRY, "base-url", LlmConfigKeys.FOUNDRY_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_FOUNDRY, "model", LlmConfigKeys.FOUNDRY_MODEL);

        assertKey(ModelDefaults.PROVIDER_GEMINI, "log-requests", LlmConfigKeys.GEMINI_LOG_REQUESTS);
        assertKey(ModelDefaults.PROVIDER_GEMINI, "log-responses", LlmConfigKeys.GEMINI_LOG_RESPONSES);

        assertKey(ModelDefaults.PROVIDER_HUGGINGFACE, "api-key", LlmConfigKeys.HUGGINGFACE_API_KEY);
        assertKey(ModelDefaults.PROVIDER_HUGGINGFACE, "base-url", LlmConfigKeys.HUGGINGFACE_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_HUGGINGFACE, "model", LlmConfigKeys.HUGGINGFACE_MODEL);

        assertKey(ModelDefaults.PROVIDER_KIMCHI, "api-key", LlmConfigKeys.KIMCHI_API_KEY);
        assertKey(ModelDefaults.PROVIDER_KIMCHI, "base-url", LlmConfigKeys.KIMCHI_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_KIMCHI, "model", LlmConfigKeys.KIMCHI_MODEL);

        assertKey(ModelDefaults.PROVIDER_MISTRAL, "api-key", LlmConfigKeys.MISTRAL_API_KEY);
        assertKey(ModelDefaults.PROVIDER_MISTRAL, "base-url", LlmConfigKeys.MISTRAL_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_MISTRAL, "model", LlmConfigKeys.MISTRAL_MODEL);

        assertKey(ModelDefaults.PROVIDER_OPENCODE, "api-key", LlmConfigKeys.OPENCODE_API_KEY);
        assertKey(ModelDefaults.PROVIDER_OPENCODE, "base-url", LlmConfigKeys.OPENCODE_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_OPENCODE, "model", LlmConfigKeys.OPENCODE_MODEL);

        assertKey(ModelDefaults.PROVIDER_OPENROUTER, "site-url", LlmConfigKeys.OPENROUTER_SITE_URL);
        assertKey(ModelDefaults.PROVIDER_OPENROUTER, "app-name", LlmConfigKeys.OPENROUTER_APP_NAME);

        assertKey(ModelDefaults.PROVIDER_SARVAM, "api-key", LlmConfigKeys.SARVAM_API_KEY);
        assertKey(ModelDefaults.PROVIDER_SARVAM, "base-url", LlmConfigKeys.SARVAM_BASE_URL);
        assertKey(ModelDefaults.PROVIDER_SARVAM, "model", LlmConfigKeys.SARVAM_MODEL);
    }

    private static void assertKey(String provider, String suffix, String constant) {
        assertEquals(
                constant,
                LlmConfigKeys.providerKey(provider, suffix),
                "providerKey(" + provider + ", " + suffix + ") must equal " + constant);
    }
}
