package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.ClaudeCodeOAuthTokenExtractor;
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

    private static LlmConfigurationProvenance provenanceFor(
            String provider, boolean vaultPriority, Map<String, String> values) {
        LlmConfigurationProvenance provenance = new LlmConfigurationProvenance();
        provenance.vault = mock(SecretVault.class);
        provenance.providerCatalog = mock(LlmProviderOnboardingAdapter.class);
        provenance.claudeCodeTokenExtractor = mock(ClaudeCodeOAuthTokenExtractor.class);
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
