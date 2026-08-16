package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.ClaudeCodeOAuthTokenExtractor;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.routing.provider.LlmProviderDescriptor;
import com.amsha.core.routing.provider.LlmProviderOnboardingAdapter;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Resolves the non-secret provenance of the active LLM configuration.
 *
 * <p>This deliberately mirrors the system-vault switch used by
 * {@code ChatModelProducer}. It never returns credential material; it only reports whether the
 * effective credential is configured and which layer supplied it.</p>
 */
@ApplicationScoped
public class LlmConfigurationProvenance {

    public static final String SOURCE_ENVIRONMENT = "ENVIRONMENT";
    public static final String SOURCE_VAULT = "VAULT";
    public static final String SOURCE_DEFAULT = "DEFAULT";
    public static final String SOURCE_NONE = "NONE";

    public static final String CREDENTIAL_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String CREDENTIAL_CONFIGURED = "CONFIGURED";
    public static final String CREDENTIAL_MISSING = "MISSING";

    private static final Map<String, EnvironmentSpec> ENVIRONMENT_SPECS = Map.ofEntries(
            Map.entry(
                    ModelDefaults.PROVIDER_GROQ,
                    new EnvironmentSpec("AMSHA_GROQ_MODEL", "AMSHA_GROQ_BASE_URL", "AMSHA_GROQ_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_OPENAI,
                    new EnvironmentSpec("AMSHA_OPENAI_MODEL", "AMSHA_OPENAI_BASE_URL", "AMSHA_OPENAI_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_OLLAMA,
                    new EnvironmentSpec(
                            "quarkus.langchain4j.ollama.chat-model.model-id",
                            "quarkus.langchain4j.ollama.base-url",
                            "")),
            Map.entry(ModelDefaults.PROVIDER_CLAUDE_CODE, new EnvironmentSpec("amsha.llm.model-name", "", "")),
            Map.entry(
                    ModelDefaults.PROVIDER_OPENROUTER,
                    new EnvironmentSpec(
                            "AMSHA_OPENROUTER_MODEL", "AMSHA_OPENROUTER_BASE_URL", "AMSHA_OPENROUTER_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_NVIDIA,
                    new EnvironmentSpec("AMSHA_NVIDIA_MODEL", "AMSHA_NVIDIA_BASE_URL", "AMSHA_NVIDIA_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_CEREBRAS,
                    new EnvironmentSpec("AMSHA_CEREBRAS_MODEL", "AMSHA_CEREBRAS_BASE_URL", "AMSHA_CEREBRAS_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_SARVAM,
                    new EnvironmentSpec("AMSHA_SARVAM_MODEL", "AMSHA_SARVAM_BASE_URL", "AMSHA_SARVAM_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_OPENCODE,
                    new EnvironmentSpec("AMSHA_OPENCODE_MODEL", "AMSHA_OPENCODE_BASE_URL", "AMSHA_OPENCODE_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_GEMINI,
                    new EnvironmentSpec("AMSHA_GEMINI_MODEL", "", "AMSHA_GEMINI_API_KEY")),
            Map.entry(ModelDefaults.PROVIDER_OMLX, new EnvironmentSpec("AMSHA_OMLX_MODEL", "AMSHA_OMLX_BASE_URL", "")),
            Map.entry(
                    ModelDefaults.PROVIDER_LLAMACPP,
                    new EnvironmentSpec("AMSHA_LLAMACPP_MODEL", "AMSHA_LLAMACPP_BASE_URL", "")),
            Map.entry(
                    ModelDefaults.PROVIDER_GENERIC_OPENAI,
                    new EnvironmentSpec(
                            "AMSHA_GENERIC_OPENAI_MODEL",
                            "AMSHA_GENERIC_OPENAI_BASE_URL",
                            "AMSHA_GENERIC_OPENAI_API_KEY")),
            Map.entry(ModelDefaults.PROVIDER_INFINITE_LOOP, new EnvironmentSpec("", "", "")));

    @Inject
    SecretVault vault;

    @Inject
    LlmProviderOnboardingAdapter providerCatalog;

    @Inject
    ClaudeCodeOAuthTokenExtractor claudeCodeTokenExtractor;

    @Inject
    Config config;

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String configuredProvider;

    @ConfigProperty(name = "amsha.llm.vault-priority", defaultValue = "false")
    boolean vaultPriority;

    /** Returns the current effective provider/model/base URL and non-secret credential state. */
    public Resolution resolve() {
        Optional<String> vaultProvider =
                vaultPriority ? readVaultString(VaultConstants.LLM_PROVIDER_KEY) : Optional.empty();
        boolean vaultActive = vaultProvider.isPresent();
        String effectiveProvider = vaultActive ? vaultProvider.get() : configuredProvider;
        String normalizedProvider = normalize(effectiveProvider);
        EnvironmentSpec environmentSpec = ENVIRONMENT_SPECS.getOrDefault(normalizedProvider, EnvironmentSpec.EMPTY);
        String source =
                vaultActive ? SOURCE_VAULT : configuredProviderIsExplicitlySet() ? SOURCE_ENVIRONMENT : SOURCE_DEFAULT;

        String effectiveModel = resolveValue(
                vaultActive ? readVaultString(VaultConstants.LLM_MODEL_KEY) : Optional.empty(),
                environmentSpec.modelKey(),
                ModelDefaults.defaultModelForProvider(normalizedProvider));
        String effectiveBaseUrl = resolveValue(
                vaultActive ? readVaultString(VaultConstants.LLM_BASE_URL_KEY) : Optional.empty(),
                environmentSpec.baseUrlKey(),
                ModelDefaults.defaultBaseUrlForProvider(normalizedProvider));

        boolean requiresApiKey = providerCatalog
                .descriptor(normalizedProvider)
                .map(LlmProviderDescriptor::requiresApiKey)
                .orElse(true);
        boolean claudeCode = ModelDefaults.PROVIDER_CLAUDE_CODE.equals(normalizedProvider);
        boolean vaultCredentialConfigured = claudeCode
                ? vault.systemKeyExists(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY)
                : vaultActive && vault.systemKeyExists(VaultConstants.LLM_API_KEY);
        boolean environmentCredentialConfigured = claudeCode
                ? claudeCodeTokenExtractor != null
                        && claudeCodeTokenExtractor.extractToken().isPresent()
                : hasConfiguredEnvironmentValue(environmentSpec.apiKeyKey());
        boolean credentialConfigured = vaultCredentialConfigured || environmentCredentialConfigured;
        boolean credentialRequired = requiresApiKey || claudeCode;
        String credentialState = credentialRequired
                ? credentialConfigured ? CREDENTIAL_CONFIGURED : CREDENTIAL_MISSING
                : CREDENTIAL_NOT_REQUIRED;
        String credentialSource = !credentialRequired
                ? SOURCE_NONE
                : vaultCredentialConfigured
                        ? SOURCE_VAULT
                        : environmentCredentialConfigured ? SOURCE_ENVIRONMENT : SOURCE_NONE;
        boolean ready = !normalizedProvider.isBlank()
                && (!credentialRequired || credentialConfigured)
                && (!ModelDefaults.PROVIDER_GENERIC_OPENAI.equals(normalizedProvider) || !effectiveBaseUrl.isBlank())
                && (!ModelDefaults.PROVIDER_INFINITE_LOOP.equals(normalizedProvider)
                        || hasConfiguredEnvironmentValue("AMSHA_INFINITE_LOOP_LLM_PROVIDERS"));

        return new Resolution(
                normalizedProvider,
                effectiveModel,
                effectiveBaseUrl,
                source,
                credentialState,
                credentialSource,
                vaultPriority,
                ready);
    }

    private String resolveValue(Optional<String> vaultValue, String environmentKey, String defaultValue) {
        return vaultValue.or(() -> configValue(environmentKey)).orElse(defaultValue == null ? "" : defaultValue);
    }

    private boolean configuredProviderIsExplicitlySet() {
        ConfigValue providerValue = config().getConfigValue("amsha.llm.provider");
        String sourceName = providerValue.getSourceName();
        if (sourceName == null || sourceName.isBlank()) {
            return false;
        }
        String normalizedSource = sourceName.toLowerCase(Locale.ROOT);
        return !normalizedSource.contains("application") && !normalizedSource.contains("default");
    }

    private boolean hasConfiguredEnvironmentValue(String key) {
        return !key.isBlank() && configValue(key).isPresent();
    }

    private Optional<String> configValue(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return config().getOptionalValue(key, String.class).filter(value -> !value.isBlank());
    }

    private Config config() {
        return config != null ? config : ConfigProvider.getConfig();
    }

    private Optional<String> readVaultString(String key) {
        return vault.retrieveSystemKey(key)
                .map(vaultKey -> {
                    try (VaultKey keyHandle = vaultKey) {
                        return new String(keyHandle.get(), StandardCharsets.UTF_8).trim();
                    }
                })
                .filter(value -> !value.isBlank());
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private record EnvironmentSpec(String modelKey, String baseUrlKey, String apiKeyKey) {
        private static final EnvironmentSpec EMPTY = new EnvironmentSpec("", "", "");
    }

    public record Resolution(
            String effectiveProvider,
            String effectiveModel,
            String effectiveBaseUrl,
            String effectiveSource,
            String credentialState,
            String credentialSource,
            boolean vaultPriority,
            boolean ready) {}
}
