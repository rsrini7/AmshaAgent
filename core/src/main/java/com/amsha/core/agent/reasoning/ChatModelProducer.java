package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
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
            Map.entry(ModelDefaults.PROVIDER_NVIDIA, ModelDefaults.DEFAULT_NVIDIA_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OLLAMA, ModelDefaults.DEFAULT_OLLAMA_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OMLX, ModelDefaults.DEFAULT_OMLX_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_LLAMACPP, ModelDefaults.DEFAULT_LLAMACPP_BASE_URL),
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

        if (vaultPriority) {
            Optional<VaultKey> vaultProvider = vault.retrieveSystemKey(VaultConstants.LLM_PROVIDER_KEY);
            if (vaultProvider.isPresent()) {
                String p = new String(vaultProvider.get().get(), StandardCharsets.UTF_8);
                if (!p.isBlank()) {
                    effectiveProvider = p;
                    Optional<VaultKey> vaultKey = vault.retrieveSystemKey(VaultConstants.LLM_API_KEY);
                    Optional<VaultKey> vaultModel = vault.retrieveSystemKey(VaultConstants.LLM_MODEL_KEY);
                    effectiveKey = vaultKey.map(k -> new String(k.get(), StandardCharsets.UTF_8))
                            .orElse(null);
                    effectiveModel = vaultModel
                            .map(k -> new String(k.get(), StandardCharsets.UTF_8))
                            .orElse(null);
                    log.infof("ChatModelProducer: using vault provider=%s model=%s", effectiveProvider, effectiveModel);
                }
            }
        }

        AgentChatModelProvider providerFactory = findProvider(effectiveProvider);
        if (effectiveKey != null && effectiveModel != null) {
            String baseUrl = PROVIDER_BASE_URLS.getOrDefault(effectiveProvider, "");
            if (!baseUrl.isBlank()) {
                log.infof(
                        "ChatModelProducer: building model from vault values (provider=%s baseUrl=%s)",
                        effectiveProvider, baseUrl);
                return ChatModelFactory.createOpenAi(
                        baseUrl,
                        effectiveKey,
                        effectiveModel,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp());
            }
        }
        return providerFactory.create();
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
        throw new RuntimeException(
                "No AgentChatModelProvider found for provider='" + cleanProvider + "' (raw: '" + provider + "')");
    }

    private String clean(String value) {
        if (value == null) return null;
        return value.split("#", 2)[0].trim();
    }
}
