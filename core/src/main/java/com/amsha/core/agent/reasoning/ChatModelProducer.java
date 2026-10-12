package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultConstants;
import com.amsha.security.api.VaultKey;

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
            Map.entry(ModelDefaults.PROVIDER_FOUNDRY, ModelDefaults.DEFAULT_FOUNDRY_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_GEMINI, ModelDefaults.DEFAULT_GEMINI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_GENERIC_OPENAI, ""));

    @ConfigProperty(name = LlmConfigKeys.PROVIDER, defaultValue = ModelDefaults.PROVIDER_GROQ)
    String provider;

    @ConfigProperty(name = LlmConfigKeys.VAULT_PRIORITY, defaultValue = "false")
    boolean vaultPriority;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @Inject
    SecretVault vault;

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    BeanManager beanManager;

    private Optional<ChatModelCreationPort> findCreationPort() {
        return CoreSpiResolutionValidator.resolveOptional(beanManager, ChatModelCreationPort.class);
    }

    private ChatModelCreationPort creationPort() {
        return findCreationPort()
                .orElseThrow(
                        () -> new IllegalStateException(
                                "No ChatModelCreationPort bean found on classpath. Ensure amsha-llm-providers is included in the application."));
    }

    private record VaultLlmConfig(String provider, String key, String model, String baseUrl) {}

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        VaultLlmConfig vc = vaultPriority ? resolveVaultPriorityConfig() : resolveStandardVaultConfig();

        AgentChatModelProvider providerFactory = findProvider(vc.provider());
        String baseUrl = vc.baseUrl() == null || vc.baseUrl().isBlank()
                ? PROVIDER_BASE_URLS.getOrDefault(vc.provider(), "")
                : vc.baseUrl();
        if (hasText(vc.key()) && hasText(vc.model()) && !baseUrl.isBlank()) {
            log.infof(
                    "ChatModelProducer: building model from vault values (provider=%s baseUrl=%s)",
                    vc.provider(), baseUrl);
            return creationPort().createForAdapter(vc.provider(), baseUrl, vc.key(), vc.model(), agentConfig);
        }
        if (hasText(vc.model()) || hasText(vc.baseUrl())) {
            return createKeylessVaultModel(vc.provider(), providerFactory, baseUrl, vc.model());
        }
        return providerFactory.create();
    }

    private VaultLlmConfig resolveVaultPriorityConfig() {
        Optional<VaultKey> vaultProvider = vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY);
        if (vaultProvider.isEmpty()) {
            return new VaultLlmConfig(provider, null, null, null);
        }
        try (VaultKey pKey = vaultProvider.get()) {
            String p = new String(pKey.get(), StandardCharsets.UTF_8).trim();
            if (p.isBlank()) {
                return new VaultLlmConfig(provider, null, null, null);
            }
            String key = readVaultKeyForProvider(p);
            String model = readVaultString(VaultConstants.LLM_MODEL_KEY);
            String baseUrl = readVaultString(VaultConstants.LLM_BASE_URL_KEY);
            log.infof("ChatModelProducer: using vault provider=%s model=%s", p, model);
            return new VaultLlmConfig(p, key, model, baseUrl);
        }
    }

    private VaultLlmConfig resolveStandardVaultConfig() {
        String effectiveProv = provider;
        String key = readVaultKeyForProvider(effectiveProv);
        String model = readVaultString(VaultConstants.LLM_MODEL_KEY);
        String baseUrl = readVaultString(VaultConstants.LLM_BASE_URL_KEY);

        if (!hasText(key)) {
            Optional<VaultKey> vaultProv = vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY);
            if (vaultProv.isPresent()) {
                try (VaultKey pKey = vaultProv.get()) {
                    String vp = new String(pKey.get(), StandardCharsets.UTF_8).trim();
                    if (ModelDefaults.PROVIDER_OLLAMA.equalsIgnoreCase(vp)
                            || ModelDefaults.PROVIDER_OMLX.equalsIgnoreCase(vp)
                            || ModelDefaults.PROVIDER_LLAMACPP.equalsIgnoreCase(vp)) {
                        effectiveProv = vp;
                        log.infof("ChatModelProducer: using vault provider=%s model=%s", effectiveProv, model);
                    }
                }
            }
        }
        return new VaultLlmConfig(effectiveProv, key, model, baseUrl);
    }

    private String readVaultString(String keyName) {
        Optional<VaultKey> vk = vault.retrieveSystemKey(keyName);
        if (vk.isPresent()) {
            try (VaultKey k = vk.get()) {
                return new String(k.get(), StandardCharsets.UTF_8).trim();
            }
        }
        return null;
    }

    private String readVaultKeyForProvider(String providerName) {
        String vaultKeyName =
                switch (providerName.toLowerCase(Locale.ROOT)) {
                    case ModelDefaults.PROVIDER_CLAUDE_CODE -> VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY;
                    case ModelDefaults.PROVIDER_CODEX -> VaultConstants.CODEX_OAUTH_TOKEN_KEY;
                    case ModelDefaults.PROVIDER_AGY -> VaultConstants.AGY_OAUTH_TOKEN_KEY;
                    case ModelDefaults.PROVIDER_KIMCHI -> VaultConstants.KIMCHI_OAUTH_TOKEN_KEY;
                    default -> VaultConstants.LLM_API_KEY;
                };
        return readVaultString(vaultKeyName);
    }

    private ChatModel createKeylessVaultModel(
            String providerName, AgentChatModelProvider providerFactory, String baseUrl, String model) {
        String cleanProvider = clean(providerName);
        Optional<ChatModelCreationPort> port = findCreationPort();
        if (port.isPresent() && port.get().supportsAdapter(cleanProvider) && hasText(model)) {
            return port.get().createForAdapter(cleanProvider, baseUrl, "not-needed", model, agentConfig);
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
