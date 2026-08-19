package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.AgyOAuthTokenExtractor;
import com.amsha.core.routing.ClaudeCodeOAuthTokenExtractor;
import com.amsha.core.routing.CodexOAuthTokenExtractor;
import com.amsha.core.routing.KimchiOAuthTokenExtractor;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.routing.provider.LlmProviderDescriptor;
import com.amsha.core.routing.provider.LlmProviderOnboardingAdapter;
import com.amsha.core.routing.provider.ModelEntry;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.Test;

class LlmConfigurationProvenanceTest {

    @Test
    void keylessProviderDoesNotRequireASecret() {
        LlmConfigurationProvenance provenance = new LlmConfigurationProvenance();
        provenance.vault = mock(SecretVault.class);
        provenance.providerCatalog = mock(LlmProviderOnboardingAdapter.class);
        provenance.config = mockConfig(Map.of(), "test-profile");
        provenance.configuredProvider = "ollama";
        provenance.vaultPriority = false;
        when(provenance.providerCatalog.descriptor("ollama"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "ollama",
                        "Ollama",
                        "ollama",
                        "NONE",
                        "http://localhost:11434",
                        false,
                        false,
                        List.of(new ModelEntry("mistral", "Mistral", null)),
                        Map.of())));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("ollama", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_NOT_REQUIRED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_NONE, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void vaultPriorityReportsSystemVaultAsTheEffectiveSource() {
        LlmConfigurationProvenance provenance = new LlmConfigurationProvenance();
        provenance.vault = mock(SecretVault.class);
        provenance.providerCatalog = mock(LlmProviderOnboardingAdapter.class);
        provenance.config = mockConfig(Map.of(), "test-profile");
        provenance.configuredProvider = "groq";
        provenance.vaultPriority = true;
        when(provenance.providerCatalog.descriptor("groq"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "groq",
                        "Groq",
                        "groq",
                        "API_KEY",
                        "https://api.groq.com/openai/v1",
                        true,
                        false,
                        List.of(new ModelEntry("model-from-vault", "Model", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.LLM_API_KEY)).thenReturn(true);
        when(provenance.vault.retrieveSystemKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return switch (key) {
                case VaultConstants.LLM_PROVIDER_KEY -> Optional.of(vaultKey("groq"));
                case VaultConstants.LLM_MODEL_KEY -> Optional.of(vaultKey("model-from-vault"));
                case VaultConstants.LLM_BASE_URL_KEY -> Optional.of(vaultKey("https://vault.example/v1"));
                default -> Optional.of(vaultKey("vault-secret"));
            };
        });

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.SOURCE_VAULT, resolution.effectiveSource());
        assertEquals("https://vault.example/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_VAULT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void environmentCredentialIsReportedWithoutVaultPriority() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "groq",
                false,
                Map.of(
                        "AMSHA_GROQ_API_KEY", "env-key",
                        "AMSHA_GROQ_MODEL", "env-model",
                        "AMSHA_GROQ_BASE_URL", "https://env.example/v1"));
        when(provenance.providerCatalog.descriptor("groq")).thenReturn(Optional.of(requiredDescriptor("groq")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("env-model", resolution.effectiveModel());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void genericOpenAiRequiresAnEffectiveBaseUrl() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "generic-openai",
                false,
                Map.of(
                        "AMSHA_GENERIC_OPENAI_API_KEY", "env-key",
                        "AMSHA_GENERIC_OPENAI_MODEL", "env-model"));
        when(provenance.providerCatalog.descriptor("generic-openai"))
                .thenReturn(Optional.of(requiredDescriptor("generic-openai")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertTrue(resolution.effectiveBaseUrl().isBlank());
        assertTrue(!resolution.ready());
    }

    @Test
    void infiniteLoopRequiresConfiguredSlots() {
        LlmConfigurationProvenance provenance = provenanceFor("infinite-loop", false, Map.of());
        when(provenance.providerCatalog.descriptor("infinite-loop"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "infinite-loop",
                        "Infinite Loop",
                        "infinite-loop",
                        "NONE",
                        "",
                        false,
                        false,
                        List.of(),
                        Map.of())));

        assertTrue(!provenance.resolve().ready());
    }

    @Test
    void claudeCodeRequiresOAuthCredentialFromVaultOrCliFile() {
        LlmConfigurationProvenance provenance = provenanceFor("claude-code", false, Map.of());
        when(provenance.providerCatalog.descriptor("claude-code"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "claude-code",
                        "Claude Code",
                        "claude-code",
                        "CLI_OAUTH",
                        "",
                        false,
                        false,
                        List.of(new ModelEntry("claude-sonnet-4-5", "Claude", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(false);
        when(provenance.claudeCodeTokenExtractor.extractToken()).thenReturn(Optional.empty());

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_MISSING, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_NONE, resolution.credentialSource());
        assertTrue(!resolution.ready());
    }

    @Test
    void claudeCodeIsReadyWhenCliOAuthFileIsAvailable() {
        LlmConfigurationProvenance provenance = provenanceFor("claude-code", false, Map.of());
        when(provenance.providerCatalog.descriptor("claude-code"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "claude-code",
                        "Claude Code",
                        "claude-code",
                        "CLI_OAUTH",
                        "",
                        false,
                        false,
                        List.of(new ModelEntry("claude-sonnet-4-5", "Claude", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(false);
        when(provenance.claudeCodeTokenExtractor.extractToken()).thenReturn(Optional.of("oauth-token"));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void claudeCodeIsReadyWhenOAuthTokenIsInVault() {
        LlmConfigurationProvenance provenance = provenanceFor("claude-code", false, Map.of());
        when(provenance.providerCatalog.descriptor("claude-code"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "claude-code",
                        "Claude Code",
                        "claude-code",
                        "CLI_OAUTH",
                        "",
                        false,
                        false,
                        List.of(new ModelEntry("claude-sonnet-4-5", "Claude", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY))
                .thenReturn(true);

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_VAULT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void agyRequiresOAuthCredentialFromVaultOrCliFile() {
        LlmConfigurationProvenance provenance = provenanceFor("agy", false, Map.of());
        when(provenance.providerCatalog.descriptor("agy"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "agy",
                        "Antigravity CLI",
                        "agy",
                        "CLI_OAUTH",
                        "",
                        false,
                        false,
                        List.of(new ModelEntry("gemini-3.1-flash-lite", "Gemini", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.AGY_OAUTH_TOKEN_KEY))
                .thenReturn(false);
        when(provenance.agyTokenExtractor.extractToken()).thenReturn(Optional.empty());

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_MISSING, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_NONE, resolution.credentialSource());
        assertTrue(!resolution.ready());
    }

    @Test
    void agyIsReadyWhenCliOAuthFileIsAvailable() {
        LlmConfigurationProvenance provenance = provenanceFor("agy", false, Map.of());
        when(provenance.providerCatalog.descriptor("agy"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "agy",
                        "Antigravity CLI",
                        "agy",
                        "CLI_OAUTH",
                        "",
                        false,
                        false,
                        List.of(new ModelEntry("gemini-3.1-flash-lite", "Gemini", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.AGY_OAUTH_TOKEN_KEY))
                .thenReturn(false);
        when(provenance.agyTokenExtractor.extractToken()).thenReturn(Optional.of("oauth-token"));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void agyIsReadyWhenOAuthTokenIsInVault() {
        LlmConfigurationProvenance provenance = provenanceFor("agy", false, Map.of());
        when(provenance.providerCatalog.descriptor("agy"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "agy",
                        "Antigravity CLI",
                        "agy",
                        "CLI_OAUTH",
                        "",
                        false,
                        false,
                        List.of(new ModelEntry("gemini-3.1-flash-lite", "Gemini", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.AGY_OAUTH_TOKEN_KEY))
                .thenReturn(true);

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_VAULT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void kimchiRequiresOAuthCredentialFromVaultOrCliFile() {
        LlmConfigurationProvenance provenance = provenanceFor("kimchi", false, Map.of());
        when(provenance.providerCatalog.descriptor("kimchi"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "kimchi",
                        "Kimchi",
                        "kimchi",
                        "CLI_OAUTH",
                        "https://llm.kimchi.dev/openai/v1",
                        false,
                        false,
                        List.of(new ModelEntry("minimax-m3", "MiniMax M3", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(false);
        when(provenance.kimchiTokenExtractor.extractToken()).thenReturn(Optional.empty());

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_MISSING, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_NONE, resolution.credentialSource());
        assertFalse(resolution.ready());
    }

    @Test
    void kimchiIsReadyWhenCliFileIsAvailable() {
        LlmConfigurationProvenance provenance = provenanceFor("kimchi", false, Map.of());
        when(provenance.providerCatalog.descriptor("kimchi"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "kimchi",
                        "Kimchi",
                        "kimchi",
                        "CLI_OAUTH",
                        "https://llm.kimchi.dev/openai/v1",
                        false,
                        false,
                        List.of(new ModelEntry("minimax-m3", "MiniMax M3", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(false);
        when(provenance.kimchiTokenExtractor.extractToken()).thenReturn(Optional.of("kimchi-key-123"));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void kimchiIsReadyWhenOAuthTokenIsInVault() {
        LlmConfigurationProvenance provenance = provenanceFor("kimchi", false, Map.of());
        when(provenance.providerCatalog.descriptor("kimchi"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "kimchi",
                        "Kimchi",
                        "kimchi",
                        "CLI_OAUTH",
                        "https://llm.kimchi.dev/openai/v1",
                        false,
                        false,
                        List.of(new ModelEntry("minimax-m3", "MiniMax M3", null)),
                        Map.of())));
        when(provenance.vault.systemKeyExists(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY))
                .thenReturn(true);

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_VAULT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void defaultProviderSourceIsAvailableWhenConfigComesFromApplicationDefaults() {
        LlmConfigurationProvenance provenance = provenanceFor("ollama", false, Map.of());
        provenance.config = mockConfig(Map.of(), "application.yaml");
        when(provenance.providerCatalog.descriptor("ollama"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "ollama",
                        "Ollama",
                        "ollama",
                        "NONE",
                        "http://localhost:11434",
                        false,
                        false,
                        List.of(new ModelEntry("mistral", "Mistral", null)),
                        Map.of())));

        assertEquals(
                LlmConfigurationProvenance.SOURCE_DEFAULT, provenance.resolve().effectiveSource());
    }

    @Test
    void partialVaultUsesEnvironmentCredentialWhenRuntimeFactoryFallsBack() {
        LlmConfigurationProvenance provenance = provenanceFor("groq", true, Map.of("AMSHA_GROQ_API_KEY", "env-key"));
        when(provenance.providerCatalog.descriptor("groq")).thenReturn(Optional.of(requiredDescriptor("groq")));
        when(provenance.vault.retrieveSystemKey(anyString())).thenAnswer(invocation -> {
            if (VaultConstants.LLM_PROVIDER_KEY.equals(invocation.getArgument(0))) {
                return Optional.of(vaultKey("groq"));
            }
            return Optional.empty();
        });

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals(LlmConfigurationProvenance.SOURCE_VAULT, resolution.effectiveSource());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertTrue(resolution.ready());
    }

    @Test
    void sarvamEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "sarvam",
                false,
                Map.of(
                        "AMSHA_SARVAM_API_KEY", "sarvam-key",
                        "AMSHA_SARVAM_MODEL", "sarvam-105b",
                        "AMSHA_SARVAM_BASE_URL", "https://api.sarvam.ai/v1"));
        when(provenance.providerCatalog.descriptor("sarvam")).thenReturn(Optional.of(requiredDescriptor("sarvam")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("sarvam", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("sarvam-105b", resolution.effectiveModel());
        assertEquals("https://api.sarvam.ai/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void opencodeEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "opencode",
                false,
                Map.of(
                        "AMSHA_OPENCODE_API_KEY", "opencode-key",
                        "AMSHA_OPENCODE_MODEL", "deepseek-v4-flash-free",
                        "AMSHA_OPENCODE_BASE_URL", "https://opencode.ai/zen/v1"));
        when(provenance.providerCatalog.descriptor("opencode")).thenReturn(Optional.of(requiredDescriptor("opencode")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("opencode", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("deepseek-v4-flash-free", resolution.effectiveModel());
        assertEquals("https://opencode.ai/zen/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void huggingfaceEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "huggingface",
                false,
                Map.of(
                        "AMSHA_HUGGINGFACE_API_KEY", "hf_key_123",
                        "AMSHA_HUGGINGFACE_MODEL", "meta-llama/Llama-3.3-70B-Instruct",
                        "AMSHA_HUGGINGFACE_BASE_URL", "https://router.huggingface.co/v1"));
        when(provenance.providerCatalog.descriptor("huggingface"))
                .thenReturn(Optional.of(requiredDescriptor("huggingface")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("huggingface", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("meta-llama/Llama-3.3-70B-Instruct", resolution.effectiveModel());
        assertEquals("https://router.huggingface.co/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void mistralEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "mistral",
                false,
                Map.of(
                        "AMSHA_MISTRAL_API_KEY", "mistral_key_123",
                        "AMSHA_MISTRAL_MODEL", "codestral-latest",
                        "AMSHA_MISTRAL_BASE_URL", "https://api.mistral.ai/v1"));
        when(provenance.providerCatalog.descriptor("mistral")).thenReturn(Optional.of(requiredDescriptor("mistral")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("mistral", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("codestral-latest", resolution.effectiveModel());
        assertEquals("https://api.mistral.ai/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void cloudflareEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "cloudflare",
                false,
                Map.of(
                        "AMSHA_CLOUDFLARE_API_KEY", "cf_key_123",
                        "AMSHA_CLOUDFLARE_MODEL", "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                        "AMSHA_CLOUDFLARE_BASE_URL", "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1"));
        when(provenance.providerCatalog.descriptor("cloudflare"))
                .thenReturn(Optional.of(requiredDescriptor("cloudflare")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("cloudflare", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("@cf/meta/llama-3.3-70b-instruct-fp8-fast", resolution.effectiveModel());
        assertEquals("https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void cohereEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "cohere",
                false,
                Map.of(
                        "AMSHA_COHERE_API_KEY", "cohere_key_123",
                        "AMSHA_COHERE_MODEL", "command-r-plus-08-2024",
                        "AMSHA_COHERE_BASE_URL", "https://api.cohere.ai/compatibility/v1"));
        when(provenance.providerCatalog.descriptor("cohere")).thenReturn(Optional.of(requiredDescriptor("cohere")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("cohere", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.effectiveSource());
        assertEquals("command-r-plus-08-2024", resolution.effectiveModel());
        assertEquals("https://api.cohere.ai/compatibility/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void cloudflareEnvironmentWithTemplateAndAccountIdIsReportedAndReady() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "cloudflare",
                false,
                Map.of(
                        "AMSHA_CLOUDFLARE_API_KEY", "cf_key_123",
                        "AMSHA_CLOUDFLARE_ACCOUNT_ID", "acc_456",
                        "AMSHA_CLOUDFLARE_MODEL", "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                        "AMSHA_CLOUDFLARE_BASE_URL",
                                "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"));
        when(provenance.providerCatalog.descriptor("cloudflare"))
                .thenReturn(Optional.of(requiredDescriptor("cloudflare")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("cloudflare", resolution.effectiveProvider());
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc_456/ai/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertTrue(resolution.ready());
    }

    @Test
    void cloudflareEnvironmentWithTemplateAndMissingAccountIdIsNotReady() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "cloudflare",
                false,
                Map.of(
                        "AMSHA_CLOUDFLARE_API_KEY", "cf_key_123",
                        "AMSHA_CLOUDFLARE_MODEL", "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                        "AMSHA_CLOUDFLARE_BASE_URL",
                                "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"));
        when(provenance.providerCatalog.descriptor("cloudflare"))
                .thenReturn(Optional.of(requiredDescriptor("cloudflare")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("cloudflare", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_MISSING, resolution.credentialState());
        assertFalse(resolution.ready());
    }

    @Test
    void codexEnvironmentCredentialIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "codex",
                false,
                Map.of(
                        "AMSHA_CODEX_API_KEY", "test-codex-key",
                        "AMSHA_CODEX_MODEL", "gpt-5.6-luna",
                        "AMSHA_CODEX_BASE_URL", "http://127.0.0.1:8787/v1"));
        when(provenance.providerCatalog.descriptor("codex")).thenReturn(Optional.of(requiredDescriptor("codex")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("codex", resolution.effectiveProvider());
        assertEquals("gpt-5.6-luna", resolution.effectiveModel());
        assertEquals("http://127.0.0.1:8787/v1", resolution.effectiveBaseUrl());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_CONFIGURED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_ENVIRONMENT, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    @Test
    void codexDefaultModelFallbackIsReported() {
        LlmConfigurationProvenance provenance = provenanceFor(
                "codex",
                false,
                Map.of(
                        "AMSHA_CODEX_API_KEY", "test-codex-key",
                        "AMSHA_CODEX_BASE_URL", "http://127.0.0.1:8787/v1"));
        when(provenance.providerCatalog.descriptor("codex")).thenReturn(Optional.of(requiredDescriptor("codex")));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("codex", resolution.effectiveProvider());
        assertEquals(ModelDefaults.DEFAULT_CODEX_MODEL, resolution.effectiveModel());
        assertTrue(resolution.ready());
    }

    @Test
    void foundryKeylessProviderDoesNotRequireSecret() {
        LlmConfigurationProvenance provenance = provenanceFor("foundry", false, Map.of());
        when(provenance.providerCatalog.descriptor("foundry"))
                .thenReturn(Optional.of(new LlmProviderDescriptor(
                        "foundry",
                        "Foundry Local",
                        "foundry",
                        "NONE",
                        "http://127.0.0.1:8000/v1",
                        false,
                        false,
                        List.of(new ModelEntry("qwen3-14b", "Qwen3 14B", null)),
                        Map.of())));

        LlmConfigurationProvenance.Resolution resolution = provenance.resolve();

        assertEquals("foundry", resolution.effectiveProvider());
        assertEquals(LlmConfigurationProvenance.CREDENTIAL_NOT_REQUIRED, resolution.credentialState());
        assertEquals(LlmConfigurationProvenance.SOURCE_NONE, resolution.credentialSource());
        assertTrue(resolution.ready());
    }

    private static LlmConfigurationProvenance provenanceFor(
            String provider, boolean vaultPriority, Map<String, String> values) {
        LlmConfigurationProvenance provenance = new LlmConfigurationProvenance();
        provenance.vault = mock(SecretVault.class);
        provenance.providerCatalog = mock(LlmProviderOnboardingAdapter.class);
        provenance.claudeCodeTokenExtractor = mock(ClaudeCodeOAuthTokenExtractor.class);
        provenance.codexTokenExtractor = mock(CodexOAuthTokenExtractor.class);
        provenance.agyTokenExtractor = mock(AgyOAuthTokenExtractor.class);
        provenance.kimchiTokenExtractor = mock(KimchiOAuthTokenExtractor.class);
        provenance.config = mockConfig(values, "test-profile");
        provenance.configuredProvider = provider;
        provenance.vaultPriority = vaultPriority;
        return provenance;
    }

    private static LlmProviderDescriptor requiredDescriptor(String provider) {
        return new LlmProviderDescriptor(
                provider,
                provider,
                provider,
                "API_KEY",
                "https://example.test/v1",
                true,
                false,
                List.of(new ModelEntry("model", "Model", null)),
                Map.of());
    }

    private static Config mockConfig(Map<String, String> values, String sourceName) {
        Config config = mock(Config.class);
        ConfigValue providerValue = mock(ConfigValue.class);
        when(providerValue.getSourceName()).thenReturn(sourceName);
        when(config.getConfigValue("amsha.llm.provider")).thenReturn(providerValue);
        when(config.getOptionalValue(anyString(), eq(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(values.get(invocation.getArgument(0))));
        return config;
    }

    private static VaultKey vaultKey(String value) {
        return new VaultKey(value.getBytes(StandardCharsets.UTF_8));
    }
}
