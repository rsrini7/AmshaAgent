package com.amsha.onboarding;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.AgyOAuthTokenExtractor;
import com.amsha.core.routing.ClaudeCodeOAuthTokenExtractor;
import com.amsha.core.routing.CodexOAuthTokenExtractor;
import com.amsha.core.routing.KimchiOAuthTokenExtractor;
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
            Map.entry(
                    ModelDefaults.PROVIDER_CLAUDE_CODE,
                    new EnvironmentSpec(
                            "AMSHA_CLAUDE_CODE_MODEL", "AMSHA_CLAUDE_CODE_BASE_URL", "AMSHA_CLAUDE_CODE_API_KEY")),
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
                    ModelDefaults.PROVIDER_HUGGINGFACE,
                    new EnvironmentSpec(
                            "AMSHA_HUGGINGFACE_MODEL", "AMSHA_HUGGINGFACE_BASE_URL", "AMSHA_HUGGINGFACE_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_MISTRAL,
                    new EnvironmentSpec("AMSHA_MISTRAL_MODEL", "AMSHA_MISTRAL_BASE_URL", "AMSHA_MISTRAL_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_CLOUDFLARE,
                    new EnvironmentSpec(
                            "AMSHA_CLOUDFLARE_MODEL", "AMSHA_CLOUDFLARE_BASE_URL", "AMSHA_CLOUDFLARE_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_COHERE,
                    new EnvironmentSpec("AMSHA_COHERE_MODEL", "AMSHA_COHERE_BASE_URL", "AMSHA_COHERE_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_CODEX,
                    new EnvironmentSpec("AMSHA_CODEX_MODEL", "AMSHA_CODEX_BASE_URL", "AMSHA_CODEX_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_AGY,
                    new EnvironmentSpec("AMSHA_AGY_MODEL", "AMSHA_AGY_BASE_URL", "AMSHA_AGY_API_KEY")),
            Map.entry(
                    ModelDefaults.PROVIDER_KIMCHI,
                    new EnvironmentSpec("AMSHA_KIMCHI_MODEL", "AMSHA_KIMCHI_BASE_URL", "AMSHA_KIMCHI_API_KEY")),
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
    CodexOAuthTokenExtractor codexTokenExtractor;

    @Inject
    AgyOAuthTokenExtractor agyTokenExtractor;

    @Inject
    KimchiOAuthTokenExtractor kimchiTokenExtractor;

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
            Optional<String> accountId =
                    configValue("AMSHA_CLOUDFLARE_ACCOUNT_ID").or(() -> configValue("amsha.llm.cloudflare.account-id"));
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
