package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OmlxProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_OMLX_API_KEY", defaultValue = "not-needed")
    String apiKey;

    @ConfigProperty(name = "ARCCLAW_OMLX_BASE_URL", defaultValue = "http://127.0.0.1:8000/v1")
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
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ChatModel create() {
        String model = modelName.orElseThrow(() -> new IllegalStateException("OMLX requires ARCCLAW_OMLX_MODEL"));
        // Disable Qwen/DeepSeek thinking mode — puts chain-of-thought in content field
        Map<String, Object> customParams = Map.of("enable_thinking", false);
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                apiKey,
                model,
                "omlx",
                (k, m) -> ChatModelFactory.createOpenAi(
                        baseUrl,
                        k,
                        m,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp(),
                        customParams));
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel("omlx", entries, 5);
    }
}
