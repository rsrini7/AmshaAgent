package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GroqProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_GROQ_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_GROQ_BASE_URL",
                    defaultValue = "https://api.groq.com/openai/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_GROQ_MODEL",
                    defaultValue = "llama-3.3-70b-versatile")
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
    public ChatModel create() {
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "Groq requires ARCCLAW_GROQ_API_KEY"));
        return ChatModelFactory.createOpenAi(
                baseUrl, key, modelName,
                agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                agentConfig.temperature(), agentConfig.logHttp());
    }
}