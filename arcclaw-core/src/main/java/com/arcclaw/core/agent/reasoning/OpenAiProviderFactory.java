package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenAiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_OPENAI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_OPENAI_BASE_URL",
                    defaultValue = "https://api.openai.com/v1")
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
    public ChatModel create() {
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "OpenAI requires ARCCLAW_OPENAI_API_KEY"));
        String model = modelName.orElseThrow(() -> new IllegalStateException(
                "OpenAI requires ARCCLAW_OPENAI_MODEL"));
        return ChatModelFactory.createOpenAi(
                baseUrl, key, model,
                agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                agentConfig.temperature(), agentConfig.logHttp());
    }
}
