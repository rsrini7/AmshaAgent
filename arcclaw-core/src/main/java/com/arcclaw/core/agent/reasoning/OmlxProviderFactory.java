package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OmlxProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_OMLX_API_KEY", defaultValue = "not-needed")
    String apiKey;

    @ConfigProperty(name = "ARCCLAW_OMLX_BASE_URL",
                    defaultValue = "http://127.0.0.1:8000/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_OMLX_MODEL")
    Optional<String> modelName;

    @Override
    public String providerName() {
        return "omlx";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        String model = modelName.orElseThrow(() -> new IllegalStateException(
                "OMLX requires ARCCLAW_OMLX_MODEL"));
        return ChatModelFactory.createOpenAi(
                baseUrl, apiKey, model,
                agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                agentConfig.temperature(), agentConfig.logHttp());
    }
}
