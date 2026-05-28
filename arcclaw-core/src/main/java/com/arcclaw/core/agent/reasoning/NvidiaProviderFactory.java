package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.MultiModelChatModel;
import com.arcclaw.core.routing.NvidiaMessageSanitizer;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NvidiaProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_NVIDIA_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_NVIDIA_BASE_URL", defaultValue = "https://integrate.api.nvidia.com/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_NVIDIA_MODEL", defaultValue = "bytedance/seed-oss-36b-instruct")
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Nvidia requires ARCCLAW_NVIDIA_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                modelName,
                "nvidia",
                (k, m) -> new NvidiaMessageSanitizer(ChatModelFactory.createOpenAi(
                        baseUrl,
                        k,
                        m,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp())));
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel("nvidia", entries, 5);
    }
}
