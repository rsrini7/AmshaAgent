package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
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

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OmlxProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_OMLX_API_KEY", defaultValue = "not-needed")
    String apiKey;

    @ConfigProperty(name = "AMSHA_OMLX_BASE_URL", defaultValue = ModelDefaults.DEFAULT_OMLX_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "AMSHA_OMLX_MODEL")
    Optional<String> modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_OMLX;
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
        return doCreate(modelName.orElseThrow(() -> new IllegalStateException("OMLX requires AMSHA_OMLX_MODEL")));
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        String model = overrideModel;
        // Disable Qwen/DeepSeek thinking mode — puts chain-of-thought in content field
        Map<String, Object> customParams = Map.of("enable_thinking", false);
        List<MultiModelChatModel.ModelEntry> entries =
                ChatModelFactory.buildMultiModelEntries(apiKey, model, "omlx", (k, m) -> {
                    ChatModel raw = ChatModelFactory.createOpenAi(
                            baseUrl,
                            k,
                            m,
                            agentConfig.maxTokens(),
                            agentConfig.timeout(),
                            agentConfig.maxRetries(),
                            agentConfig.temperature(),
                            agentConfig.logHttp(),
                            customParams);
                    MultimodalCapability cap = MultimodalCapabilities.defaultCapabilityForModel("omlx", m);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel("omlx", entries, 5);
    }
}
