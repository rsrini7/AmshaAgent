package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/** Agent-reasoning provider for Cohere (OpenAI Compatibility API). */
@ApplicationScoped
@Unremovable
public class CohereProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "amsha.llm.cohere.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "amsha.llm.cohere.base-url", defaultValue = ModelDefaults.DEFAULT_COHERE_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.cohere.model", defaultValue = ModelDefaults.DEFAULT_COHERE_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_COHERE;
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
        String key = apiKey.filter(k -> !k.isBlank())
                .or(() -> Optional.ofNullable(System.getProperty("AMSHA_COHERE_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getProperty("amsha.llm.cohere.api-key"))
                        .filter(k -> !k.isBlank()))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_COHERE_API_KEY"))
                        .filter(k -> !k.isBlank()))
                .orElseThrow(() -> new IllegalStateException("Cohere requires AMSHA_COHERE_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                model,
                ModelDefaults.PROVIDER_COHERE,
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
        return new MultiModelChatModel(ModelDefaults.PROVIDER_COHERE, entries, 5);
    }
}
