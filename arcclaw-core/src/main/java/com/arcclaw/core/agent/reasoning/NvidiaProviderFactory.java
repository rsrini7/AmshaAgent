package com.arcclaw.core.agent.reasoning;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.NvidiaMessageSanitizer;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NvidiaProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_NVIDIA_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_NVIDIA_BASE_URL", 
                    defaultValue = "https://integrate.api.nvidia.com/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_NVIDIA_MODEL",
                    defaultValue = "mistralai/devstral-2-123b-instruct-2512")
    String modelName;

    @Override
    public String providerName() {
        return "nvidia";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "Nvidia requires ARCCLAW_NVIDIA_API_KEY"));
        ChatModel base = ChatModelFactory.createOpenAi(
                baseUrl, key, modelName,
                agentConfig.maxTokens(), agentConfig.timeout(), agentConfig.maxRetries(),
                agentConfig.temperature(), agentConfig.logHttp());

        return new NvidiaMessageSanitizer(base);
    }
}
