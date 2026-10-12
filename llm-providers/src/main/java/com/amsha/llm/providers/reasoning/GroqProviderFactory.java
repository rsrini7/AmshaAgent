package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
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
public class GroqProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = ConfigKeys.GROQ_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = ConfigKeys.GROQ_BASE_URL, defaultValue = ModelDefaults.DEFAULT_GROQ_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = ConfigKeys.GROQ_MODEL, defaultValue = ModelDefaults.DEFAULT_GROQ_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_GROQ;
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
        return createModel(modelName);
    }

    @Override
    public ChatModel create(String customModelName) {
        return createModel(customModelName);
    }

    private ChatModel createModel(String model) {
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Groq requires AMSHA_GROQ_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries =
                ChatModelFactory.buildMultiModelEntries(key, model, ModelDefaults.PROVIDER_GROQ, (k, m) -> {
                    ChatModel raw = ChatModelFactory.createOpenAi(
                            baseUrl,
                            k,
                            m,
                            agentConfig.maxTokens(),
                            agentConfig.timeout(),
                            agentConfig.maxRetries(),
                            agentConfig.temperature(),
                            agentConfig.logHttp());
                    MultimodalCapability cap =
                            MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_GROQ, m);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_GROQ, entries, 5);
    }
}
