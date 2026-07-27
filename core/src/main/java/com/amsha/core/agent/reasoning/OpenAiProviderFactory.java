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

@ApplicationScoped
public class OpenAiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_OPENAI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "AMSHA_OPENAI_BASE_URL", defaultValue = ModelDefaults.DEFAULT_OPENAI_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "AMSHA_OPENAI_MODEL")
    Optional<String> modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_OPENAI;
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
        return doCreate(modelName.orElseThrow(() -> new IllegalStateException("OpenAI requires AMSHA_OPENAI_MODEL")));
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        String key = apiKey.orElseThrow(() -> new IllegalStateException("OpenAI requires AMSHA_OPENAI_API_KEY"));
        String model = overrideModel;
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                model,
                "openai",
                (k, m) -> ChatModelFactory.createOpenAi(
                        baseUrl,
                        k,
                        m,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp()));
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel("openai", entries, 5);
    }
}
