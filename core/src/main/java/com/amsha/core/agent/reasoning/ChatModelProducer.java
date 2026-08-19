package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.ChatModelFactory;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.core.security.VaultKey;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;

@ApplicationScoped
@Unremovable
public class ChatModelProducer {

    private static final Logger log = Logger.getLogger(ChatModelProducer.class);
    private static final Map<String, String> PROVIDER_BASE_URLS = Map.ofEntries(
            Map.entry(ModelDefaults.PROVIDER_GROQ, ModelDefaults.DEFAULT_GROQ_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OPENAI, ModelDefaults.DEFAULT_OPENAI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.DEFAULT_OPENROUTER_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CEREBRAS, ModelDefaults.DEFAULT_CEREBRAS_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_SARVAM, ModelDefaults.DEFAULT_SARVAM_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OPENCODE, ModelDefaults.DEFAULT_OPENCODE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_HUGGINGFACE, ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_MISTRAL, ModelDefaults.DEFAULT_MISTRAL_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CLOUDFLARE, ModelDefaults.DEFAULT_CLOUDFLARE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_COHERE, ModelDefaults.DEFAULT_COHERE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CODEX, ModelDefaults.DEFAULT_CODEX_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_NVIDIA, ModelDefaults.DEFAULT_NVIDIA_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OLLAMA, ModelDefaults.DEFAULT_OLLAMA_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OMLX, ModelDefaults.DEFAULT_OMLX_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_LLAMACPP, ModelDefaults.DEFAULT_LLAMACPP_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_ANTHROPIC, ModelDefaults.DEFAULT_ANTHROPIC_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CLAUDE_CODE, ModelDefaults.DEFAULT_CLAUDE_CODE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_AGY, ModelDefaults.DEFAULT_AGY_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_KIMCHI, ModelDefaults.DEFAULT_KIMCHI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_GEMINI, ModelDefaults.DEFAULT_GEMINI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_GENERIC_OPENAI, ""));

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "amsha.llm.vault-priority", defaultValue = "false")
    boolean vaultPriority;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @Inject
    SecretVault vault;

    @Inject
    AgentLlmConfig agentConfig;

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        String effectiveProvider = provider;
        String effectiveKey = null;
        String effectiveModel = null;
        String effectiveBaseUrl = null;

        if (vaultPriority) {
            Optional<VaultKey> vaultProvider = vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY);
            if (vaultProvider.isPresent()) {
                try (VaultKey pKey = vaultProvider.get()) {
                    String p = new String(pKey.get(), StandardCharsets.UTF_8).trim();
                    if (!p.isBlank()) {
                        effectiveProvider = p;

                        String vaultKeyName =
                                switch (p.toLowerCase(Locale.ROOT)) {
                                    case ModelDefaults.PROVIDER_CLAUDE_CODE ->
                                        VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY;
                                    case ModelDefaults.PROVIDER_CODEX -> VaultConstants.CODEX_OAUTH_TOKEN_KEY;
                                    case ModelDefaults.PROVIDER_AGY -> VaultConstants.AGY_OAUTH_TOKEN_KEY;
                                    case ModelDefaults.PROVIDER_KIMCHI -> VaultConstants.KIMCHI_OAUTH_TOKEN_KEY;
                                    default -> VaultConstants.LLM_API_KEY;
                                };

                        Optional<VaultKey> vaultKey = vault.retrieveSystemKey(vaultKeyName);
                        if (vaultKey.isPresent()) {
                            try (VaultKey vk = vaultKey.get()) {
                                effectiveKey = new String(vk.get(), StandardCharsets.UTF_8).trim();
                            }
                        }

                        Optional<VaultKey> vaultModel = vault.retrieveSystemKey(VaultConstants.LLM_MODEL_KEY);
                        if (vaultModel.isPresent()) {
                            try (VaultKey vm = vaultModel.get()) {
                                effectiveModel = new String(vm.get(), StandardCharsets.UTF_8).trim();
                            }
                        }

                        Optional<VaultKey> vaultBaseUrl = vault.retrieveSystemKey(VaultConstants.LLM_BASE_URL_KEY);
                        if (vaultBaseUrl.isPresent()) {
                            try (VaultKey vb = vaultBaseUrl.get()) {
                                effectiveBaseUrl = new String(vb.get(), StandardCharsets.UTF_8).trim();
                            }
                        }

                        log.infof(
                                "ChatModelProducer: using vault provider=%s model=%s",
                                effectiveProvider, effectiveModel);
                    }
                }
            }
        }

        AgentChatModelProvider providerFactory = findProvider(effectiveProvider);
        String baseUrl = effectiveBaseUrl == null || effectiveBaseUrl.isBlank()
                ? PROVIDER_BASE_URLS.getOrDefault(effectiveProvider, "")
                : effectiveBaseUrl;
        if (hasText(effectiveKey) && hasText(effectiveModel) && !baseUrl.isBlank()) {
            log.infof(
                    "ChatModelProducer: building model from vault values (provider=%s baseUrl=%s)",
                    effectiveProvider, baseUrl);
            return ChatModelFactory.createForAdapter(
                    effectiveProvider, baseUrl, effectiveKey, effectiveModel, agentConfig);
        }
        if (hasText(effectiveModel) || hasText(effectiveBaseUrl)) {
            return createKeylessVaultModel(effectiveProvider, providerFactory, baseUrl, effectiveModel);
        }
        return providerFactory.create();
    }

    private ChatModel createKeylessVaultModel(
            String providerName, AgentChatModelProvider providerFactory, String baseUrl, String model) {
        String cleanProvider = clean(providerName);
        if ("ollama".equalsIgnoreCase(cleanProvider)) {
            return ChatModelFactory.createOllama(baseUrl, model, agentConfig);
        }
        if (("omlx".equalsIgnoreCase(cleanProvider) || "llamacpp".equalsIgnoreCase(cleanProvider))
                && hasText(model)
                && !baseUrl.isBlank()) {
            return ChatModelFactory.createOpenAi(
                    baseUrl,
                    "not-needed",
                    model,
                    agentConfig.maxTokens(),
                    agentConfig.timeout(),
                    agentConfig.maxRetries(),
                    agentConfig.temperature(),
                    agentConfig.logHttp());
        }
        return hasText(model) ? providerFactory.create(model) : providerFactory.create();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveProvider() {
        if (vaultPriority) {
            Optional<VaultKey> vaultKey = vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY);
            if (vaultKey.isPresent()) {
                String p = new String(vaultKey.get().get(), StandardCharsets.UTF_8);
                if (!p.isBlank()) return p;
            }
        }
        return provider != null ? provider : ModelDefaults.DEFAULT_PROVIDER;
    }

    @Produces
    @Identifier("agent-output-mode")
    @Dependent
    @Unremovable
    public StructuredOutputMode structuredOutputMode() {
        return findProvider(resolveProvider()).structuredOutputMode();
    }

    private AgentChatModelProvider findProvider(String providerName) {
        String cleanProvider = clean(providerName != null ? providerName : ModelDefaults.DEFAULT_PROVIDER);
        for (AgentChatModelProvider p : providers) {
            if (p.providerName().equalsIgnoreCase(cleanProvider)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "No AgentChatModelProvider found for provider='" + cleanProvider + "' (raw: '" + provider + "')");
    }

    private String clean(String value) {
        if (value == null) return null;
        return value.split("#", 2)[0].trim();
    }
}
