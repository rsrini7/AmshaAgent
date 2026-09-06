package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.CapabilityAwareChatModel;
import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.wrapper.NvidiaMessageSanitizer;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class NvidiaProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_NVIDIA_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "AMSHA_NVIDIA_BASE_URL", defaultValue = ModelDefaults.DEFAULT_NVIDIA_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "AMSHA_NVIDIA_MODEL", defaultValue = ModelDefaults.DEFAULT_NVIDIA_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_NVIDIA;
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ChatModel create() {
        return doCreate(modelName);
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String modelName) {
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Nvidia requires AMSHA_NVIDIA_API_KEY"));
        String cleanKey = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();
        List<MultiModelChatModel.ModelEntry> entries =
                ChatModelFactory.buildMultiModelEntries(cleanKey, modelName, "nvidia", (k, m) -> {
                    ChatModel raw = new NvidiaMessageSanitizer(ChatModelFactory.createOpenAi(
                            baseUrl,
                            k,
                            m,
                            agentConfig.maxTokens(),
                            agentConfig.timeout(),
                            agentConfig.maxRetries(),
                            agentConfig.temperature(),
                            agentConfig.logHttp()));
                    MultimodalCapability cap = MultimodalCapabilities.defaultCapabilityForModel("nvidia", m);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel("nvidia", entries, 5);
    }
}
