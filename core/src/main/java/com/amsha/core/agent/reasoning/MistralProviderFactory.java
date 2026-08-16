package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.ChatModelFactory;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Agent-reasoning provider for Mistral AI / La Plateforme. */
@ApplicationScoped
public class MistralProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "amsha.llm.mistral.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "amsha.llm.mistral.base-url", defaultValue = ModelDefaults.DEFAULT_MISTRAL_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.mistral.model", defaultValue = ModelDefaults.DEFAULT_MISTRAL_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_MISTRAL;
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Mistral AI requires AMSHA_MISTRAL_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                model,
                ModelDefaults.PROVIDER_MISTRAL,
                (entryKey, entryModel) -> ChatModelFactory.createOpenAi(
                        baseUrl,
                        entryKey,
                        entryModel,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp()));
        if (entries.size() == 1) return entries.getFirst().model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_MISTRAL, entries, 5);
    }
}
