package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.ChatModelFactory;
import com.amsha.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GroqProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_GROQ_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "AMSHA_GROQ_BASE_URL", defaultValue = "https://api.groq.com/openai/v1")
    String baseUrl;

    @ConfigProperty(name = "AMSHA_GROQ_MODEL", defaultValue = "meta-llama/llama-4-scout-17b-16e-instruct")
    String modelName;

    @Override
    public String providerName() {
        return "groq";
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
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                model,
                "groq",
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
        return new MultiModelChatModel("groq", entries, 5);
    }
}
