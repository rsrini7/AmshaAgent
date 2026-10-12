package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.LlmProviderDescriptor;
import com.amsha.llm.api.LlmProviderOnboardingAdapter;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.oauth.AgyOAuthTokenExtractor;
import com.amsha.llm.api.oauth.ClaudeCodeOAuthTokenExtractor;
import com.amsha.llm.api.oauth.CodexOAuthTokenExtractor;
import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

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
                    new EnvironmentSpec(ConfigKeys.GROQ_MODEL, ConfigKeys.GROQ_BASE_URL, ConfigKeys.GROQ_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_OPENAI,
                    new EnvironmentSpec(
                            ConfigKeys.OPENAI_MODEL, ConfigKeys.OPENAI_BASE_URL, ConfigKeys.OPENAI_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_OLLAMA,
                    new EnvironmentSpec(
                            "quarkus.langchain4j.ollama.chat-model.model-id",
                            "quarkus.langchain4j.ollama.base-url",
                            "")),
            Map.entry(
                    ModelDefaults.PROVIDER_CLAUDE_CODE,
                    new EnvironmentSpec(
                            ConfigKeys.CLAUDE_CODE_MODEL,
                            ConfigKeys.CLAUDE_CODE_BASE_URL,
                            ConfigKeys.CLAUDE_CODE_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_OPENROUTER,
                    new EnvironmentSpec(
                            ConfigKeys.OPENROUTER_MODEL,
                            ConfigKeys.OPENROUTER_BASE_URL,
                            ConfigKeys.OPENROUTER_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_NVIDIA,
                    new EnvironmentSpec(
                            ConfigKeys.NVIDIA_MODEL, ConfigKeys.NVIDIA_BASE_URL, ConfigKeys.NVIDIA_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_CEREBRAS,
                    new EnvironmentSpec(
                            ConfigKeys.CEREBRAS_MODEL, ConfigKeys.CEREBRAS_BASE_URL, ConfigKeys.CEREBRAS_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_SARVAM,
                    new EnvironmentSpec(
                            ConfigKeys.SARVAM_MODEL, ConfigKeys.SARVAM_BASE_URL, ConfigKeys.SARVAM_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_OPENCODE,
                    new EnvironmentSpec(
                            ConfigKeys.OPENCODE_MODEL, ConfigKeys.OPENCODE_BASE_URL, ConfigKeys.OPENCODE_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_HUGGINGFACE,
                    new EnvironmentSpec(
                            ConfigKeys.HUGGINGFACE_MODEL,
                            ConfigKeys.HUGGINGFACE_BASE_URL,
                            ConfigKeys.HUGGINGFACE_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_MISTRAL,
                    new EnvironmentSpec(
                            ConfigKeys.MISTRAL_MODEL, ConfigKeys.MISTRAL_BASE_URL, ConfigKeys.MISTRAL_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_CLOUDFLARE,
                    new EnvironmentSpec(
                            ConfigKeys.CLOUDFLARE_MODEL,
                            ConfigKeys.CLOUDFLARE_BASE_URL,
                            ConfigKeys.CLOUDFLARE_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_COHERE,
                    new EnvironmentSpec(
                            ConfigKeys.COHERE_MODEL, ConfigKeys.COHERE_BASE_URL, ConfigKeys.COHERE_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_CODEX,
                    new EnvironmentSpec(ConfigKeys.CODEX_MODEL, ConfigKeys.CODEX_BASE_URL, ConfigKeys.CODEX_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_AGY,
                    new EnvironmentSpec(ConfigKeys.AGY_MODEL, ConfigKeys.AGY_BASE_URL, ConfigKeys.AGY_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_KIMCHI,
                    new EnvironmentSpec(
                            ConfigKeys.KIMCHI_MODEL, ConfigKeys.KIMCHI_BASE_URL, ConfigKeys.KIMCHI_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_FOUNDRY,
                    new EnvironmentSpec(ConfigKeys.FOUNDRY_MODEL, ConfigKeys.FOUNDRY_BASE_URL, "")),
            Map.entry(
                    ModelDefaults.PROVIDER_GEMINI,
                    new EnvironmentSpec(ConfigKeys.GEMINI_MODEL, "", ConfigKeys.GEMINI_API_KEY)),
            Map.entry(
                    ModelDefaults.PROVIDER_OMLX,
                    new EnvironmentSpec(ConfigKeys.OMLX_MODEL, ConfigKeys.OMLX_BASE_URL, "")),
            Map.entry(
                    ModelDefaults.PROVIDER_LLAMACPP,
                    new EnvironmentSpec(ConfigKeys.LLAMACPP_MODEL, ConfigKeys.LLAMACPP_BASE_URL, "")),
            Map.entry(
                    ModelDefaults.PROVIDER_GENERIC_OPENAI,
                    new EnvironmentSpec(
                            ConfigKeys.GENERIC_OPENAI_MODEL,
                            ConfigKeys.GENERIC_OPENAI_BASE_URL,
                            ConfigKeys.GENERIC_OPENAI_API_KEY)),
            Map.entry(ModelDefaults.PROVIDER_INFINITE_LOOP, new EnvironmentSpec("", "", "")));

    @Inject
    SecretVault vault;

    @Inject
    LlmProviderOnboardingAdapter providerCatalog;

    @Inject
    ClaudeCodeOAuthTokenExtractor claudeCodeTokenExtractor;

    @Inject
    CodexOAuthTokenExtractor codexTokenExtractor;

    @Inject
    AgyOAuthTokenExtractor agyTokenExtractor;

    @Inject
    KimchiOAuthTokenExtractor kimchiTokenExtractor;

    @Inject
    Config config;

    @ConfigProperty(name = LlmConfigKeys.PROVIDER, defaultValue = ModelDefaults.PROVIDER_GROQ)
    String configuredProvider;

    @ConfigProperty(name = LlmConfigKeys.VAULT_PRIORITY, defaultValue = "false")
    boolean vaultPriority;

    /** Returns the current effective provider/model/base URL and non-secret credential state. */
    public Resolution resolve() {
        Optional<String> vaultProvider =
                vaultPriority ? readVaultString(VaultConstants.LLM_PROVIDER_KEY) : Optional.empty();
        String normalizedProvider = normalize(vaultProvider.orElseGet(() -> configuredProvider));
        EnvironmentSpec environmentSpec = ENVIRONMENT_SPECS.getOrDefault(normalizedProvider, EnvironmentSpec.EMPTY);

        Optional<String> vaultModel = vaultPriority ? readVaultString(VaultConstants.LLM_MODEL_KEY) : Optional.empty();
        String effectiveModel = resolveValue(
                vaultModel, environmentSpec.modelKey(), ModelDefaults.defaultModelForProvider(normalizedProvider));

        Optional<String> vaultBaseUrl =
                vaultPriority ? readVaultString(VaultConstants.LLM_BASE_URL_KEY) : Optional.empty();
        String effectiveBaseUrl = resolveValue(
                vaultBaseUrl,
                environmentSpec.baseUrlKey(),
                ModelDefaults.defaultBaseUrlForProvider(normalizedProvider));

        String source = vaultProvider.isPresent()
                ? SOURCE_VAULT
                : configuredProviderIsExplicitlySet() ? SOURCE_ENVIRONMENT : SOURCE_DEFAULT;

        boolean requiresApiKey = providerCatalog != null
                && providerCatalog
                        .descriptor(normalizedProvider)
                        .map(LlmProviderDescriptor::requiresApiKey)
                        .orElse(true);
        boolean claudeCode = ModelDefaults.PROVIDER_CLAUDE_CODE.equals(normalizedProvider);
        boolean codex = ModelDefaults.PROVIDER_CODEX.equals(normalizedProvider);
        boolean agy = ModelDefaults.PROVIDER_AGY.equals(normalizedProvider);
        boolean kimchi = ModelDefaults.PROVIDER_KIMCHI.equals(normalizedProvider);
        boolean cloudflare = ModelDefaults.PROVIDER_CLOUDFLARE.equals(normalizedProvider);
        if (cloudflare && effectiveBaseUrl.contains("{account_id}")) {
            Optional<String> accountId = configValue(ConfigKeys.CLOUDFLARE_ACCOUNT_ID)
                    .or(() -> configValue(LlmConfigKeys.CLOUDFLARE_ACCOUNT_ID));
            if (accountId.isPresent() && !accountId.get().isBlank()) {
                effectiveBaseUrl =
                        effectiveBaseUrl.replace("{account_id}", accountId.get().trim());
            }
        }
        boolean cloudflareAccountConfigured = !cloudflare || !effectiveBaseUrl.contains("{account_id}");
        boolean vaultCredentialConfigured = isVaultCredentialConfigured(normalizedProvider, vaultProvider.isPresent());
        boolean environmentCredentialConfigured =
                isEnvironmentCredentialConfigured(normalizedProvider, environmentSpec);
        boolean credentialConfigured =
                (vaultCredentialConfigured || environmentCredentialConfigured) && cloudflareAccountConfigured;
        boolean credentialRequired = requiresApiKey || claudeCode || codex || agy || kimchi || cloudflare;

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
                && (!cloudflare || !effectiveBaseUrl.contains("{account_id}"))
                && (!ModelDefaults.PROVIDER_INFINITE_LOOP.equals(normalizedProvider)
                        || hasConfiguredEnvironmentValue(ConfigKeys.AMSHA_INFINITE_LOOP_LLM_PROVIDERS));

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
        ConfigValue providerValue = config().getConfigValue(LlmConfigKeys.PROVIDER);
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

    private boolean isVaultCredentialConfigured(String normalizedProvider, boolean vaultActive) {
        return switch (normalizedProvider) {
            case ModelDefaults.PROVIDER_CLAUDE_CODE ->
                vault.systemKeyExists(VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY);
            case ModelDefaults.PROVIDER_CODEX -> vault.systemKeyExists(VaultConstants.CODEX_OAUTH_TOKEN_KEY);
            case ModelDefaults.PROVIDER_AGY -> vault.systemKeyExists(VaultConstants.AGY_OAUTH_TOKEN_KEY);
            case ModelDefaults.PROVIDER_KIMCHI -> vault.systemKeyExists(VaultConstants.KIMCHI_OAUTH_TOKEN_KEY);
            default -> vaultActive && vault.systemKeyExists(VaultConstants.LLM_API_KEY);
        };
    }

    private boolean isEnvironmentCredentialConfigured(String normalizedProvider, EnvironmentSpec environmentSpec) {
        return switch (normalizedProvider) {
            case ModelDefaults.PROVIDER_CLAUDE_CODE ->
                (claudeCodeTokenExtractor != null
                                && claudeCodeTokenExtractor.extractToken().isPresent())
                        || hasConfiguredEnvironmentValue(environmentSpec.apiKeyKey());
            case ModelDefaults.PROVIDER_CODEX ->
                (codexTokenExtractor != null
                                && codexTokenExtractor.extractToken().isPresent())
                        || hasConfiguredEnvironmentValue(environmentSpec.apiKeyKey());
            case ModelDefaults.PROVIDER_AGY ->
                (agyTokenExtractor != null && agyTokenExtractor.extractToken().isPresent())
                        || hasConfiguredEnvironmentValue(environmentSpec.apiKeyKey());
            case ModelDefaults.PROVIDER_KIMCHI ->
                (kimchiTokenExtractor != null
                                && kimchiTokenExtractor.extractToken().isPresent())
                        || hasConfiguredEnvironmentValue(environmentSpec.apiKeyKey());
            default -> hasConfiguredEnvironmentValue(environmentSpec.apiKeyKey());
        };
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
