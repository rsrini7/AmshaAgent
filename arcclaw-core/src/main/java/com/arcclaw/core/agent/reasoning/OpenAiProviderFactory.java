package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenAiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_OPENAI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_OPENAI_BASE_URL", defaultValue = "https://api.openai.com/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_OPENAI_MODEL")
    Optional<String> modelName;

    @Override
    public String providerName() {
        return "openai";
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException("OpenAI requires ARCCLAW_OPENAI_API_KEY"));
        String model = modelName.orElseThrow(() -> new IllegalStateException("OpenAI requires ARCCLAW_OPENAI_MODEL"));
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
