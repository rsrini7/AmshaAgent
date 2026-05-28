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
public class CerebrasProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_CEREBRAS_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_CEREBRAS_BASE_URL", defaultValue = "https://api.cerebras.ai/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_CEREBRAS_MODEL", defaultValue = "qwen-3-235b-a22b-instruct-2507")
    String modelName;

    @Override
    public String providerName() {
        return "cerebras";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Cerebras requires ARCCLAW_CEREBRAS_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                modelName,
                "cerebras",
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
        return new MultiModelChatModel("cerebras", entries, 5);
    }
}
